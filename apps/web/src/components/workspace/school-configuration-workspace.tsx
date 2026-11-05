"use client";

import { useCallback, useEffect, useMemo, useState, type FormEvent } from "react";
import { ArrowUpRight, Check } from "@/components/icons";
import { LaunchReadinessWorkspace } from "@/components/workspace/launch-readiness-workspace";
import { PeopleIcon, SettingsIcon } from "@/components/workspace/workspace-icons";

type Campus = {
  id: string;
  code: string;
  displayName: string;
  timeZone: string;
  status: "ACTIVE" | "INACTIVE";
  rowVersion: number;
};

type Snapshot = {
  schoolId: string;
  schoolSlug: string;
  legalName: string;
  displayName: string;
  timeZone: string;
  currencyCode: string;
  schoolStatus: string;
  boardCode: string;
  gradeRange: string;
  motto?: string;
  supportedLanguages: string[];
  publicEmail?: string;
  publicPhone?: string;
  principalDisplayName?: string;
  addressLine: string;
  locality?: string;
  city: string;
  stateName: string;
  countryCode: string;
  learnerCountMin?: number;
  learnerCountMax?: number;
  teachingStaffEstimate?: number;
  operationsStaffEstimate?: number;
  verifiedAt?: string;
  updatedAt?: string;
  rowVersion: number;
  campuses: Campus[];
};

type Tab = "identity" | "campuses" | "launch";

const apiUrl = process.env.NEXT_PUBLIC_API_URL ?? "http://localhost:8080";

function cookie(name: string) {
  return document.cookie
    .split("; ")
    .find((part) => part.startsWith(`${name}=`))
    ?.slice(name.length + 1);
}

async function csrfToken() {
  let token = cookie("educore_csrf");
  if (!token) {
    await fetch(`${apiUrl}/api/v1/auth/sessions/current`, { credentials: "include" });
    token = cookie("educore_csrf");
  }
  if (!token) throw new Error("The secure form token is unavailable. Refresh and try again.");
  return decodeURIComponent(token);
}

async function problem(response: Response, fallback: string) {
  try {
    const body = (await response.json()) as { detail?: string };
    return body.detail || fallback;
  } catch {
    return fallback;
  }
}

function optionalNumber(value: FormDataEntryValue | null) {
  const text = String(value || "").trim();
  return text ? Number(text) : null;
}

function dateLabel(value?: string) {
  if (!value) return "Awaiting authority verification";
  return new Intl.DateTimeFormat("en-IN", {
    dateStyle: "medium",
    timeStyle: "short",
  }).format(new Date(value));
}

export function SchoolConfigurationWorkspace() {
  const [data, setData] = useState<Snapshot | null>(null);
  const [tab, setTab] = useState<Tab>("identity");
  const [selectedCampusId, setSelectedCampusId] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  const load = useCallback(async () => {
    const response = await fetch(`${apiUrl}/api/v1/school-configuration`, {
      credentials: "include",
    });
    if (!response.ok) {
      throw new Error(
        await problem(response, "The protected school configuration could not be opened."),
      );
    }
    const snapshot = (await response.json()) as Snapshot;
    setData(snapshot);
    setSelectedCampusId((current) =>
      snapshot.campuses.some((campus) => campus.id === current) ? current : "",
    );
  }, []);

  useEffect(() => {
    Promise.resolve()
      .then(load)
      .catch((reason: unknown) =>
        setError(
          reason instanceof Error
            ? reason.message
            : "The protected school configuration could not be opened.",
        ),
      );
  }, [load]);

  const selectedCampus = useMemo(
    () => data?.campuses.find((campus) => campus.id === selectedCampusId) ?? null,
    [data, selectedCampusId],
  );

  async function mutate(path: string, init: RequestInit, success: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      const response = await fetch(`${apiUrl}${path}`, {
        ...init,
        credentials: "include",
        headers: {
          "Content-Type": "application/json",
          "X-CSRF-TOKEN": await csrfToken(),
          ...(init.headers || {}),
        },
      });
      if (!response.ok) {
        throw new Error(await problem(response, "The configuration could not be saved."));
      }
      await load();
      setNotice(success);
      return true;
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The configuration could not be saved.");
      return false;
    } finally {
      setBusy(false);
    }
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    const form = new FormData(event.currentTarget);
    const languages = String(form.get("supportedLanguages") || "")
      .split(",")
      .map((language) => language.trim())
      .filter(Boolean);
    const payload = {
      schoolId: data.schoolId,
      legalName: form.get("legalName"),
      displayName: form.get("displayName"),
      timeZone: form.get("timeZone"),
      currencyCode: form.get("currencyCode"),
      boardCode: form.get("boardCode"),
      gradeRange: form.get("gradeRange"),
      motto: form.get("motto") || null,
      supportedLanguages: languages,
      publicEmail: form.get("publicEmail") || null,
      publicPhone: form.get("publicPhone") || null,
      principalDisplayName: form.get("principalDisplayName") || null,
      addressLine: form.get("addressLine"),
      locality: form.get("locality") || null,
      city: form.get("city"),
      stateName: form.get("stateName"),
      countryCode: form.get("countryCode"),
      learnerCountMin: optionalNumber(form.get("learnerCountMin")),
      learnerCountMax: optionalNumber(form.get("learnerCountMax")),
      teachingStaffEstimate: optionalNumber(form.get("teachingStaffEstimate")),
      operationsStaffEstimate: optionalNumber(form.get("operationsStaffEstimate")),
      expectedVersion: data.rowVersion,
      accuracyConfirmed: form.get("accuracyConfirmed") === "on",
      reason: form.get("reason"),
    };
    await mutate(
      "/api/v1/school-configuration/profile",
      { method: "PUT", body: JSON.stringify(payload) },
      "School identity re-verified and saved with an audit event.",
    );
  }

  async function saveCampus(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!data) return;
    const form = new FormData(event.currentTarget);
    const payload = {
      schoolId: data.schoolId,
      code: form.get("code"),
      displayName: form.get("displayName"),
      timeZone: form.get("timeZone"),
      status: form.get("status"),
      expectedVersion: selectedCampus?.rowVersion ?? null,
      reason: form.get("reason"),
    };
    const saved = await mutate(
      selectedCampus
        ? `/api/v1/school-configuration/campuses/${selectedCampus.id}`
        : "/api/v1/school-configuration/campuses",
      { method: selectedCampus ? "PUT" : "POST", body: JSON.stringify(payload) },
      selectedCampus
        ? "Campus configuration saved with optimistic version protection."
        : "Campus created inside this school only.",
    );
    if (saved) setSelectedCampusId("");
  }

  if (!data && !error) {
    return (
      <section className="school-config-loading" aria-live="polite">
        <i />
        <p>Opening authority-controlled school configuration…</p>
      </section>
    );
  }

  if (!data) {
    return (
      <section className="school-config-error" role="alert">
        <SettingsIcon />
        <h1>School setup is unavailable</h1>
        <p>{error}</p>
        <button type="button" onClick={() => void load()}>
          Try again
        </button>
      </section>
    );
  }

  return (
    <section className="school-config-workspace">
      <header className="school-config-hero">
        <div>
          <span>Authority-controlled foundation</span>
          <h1>
            Configure the school, <em>without fixed details.</em>
          </h1>
          <p>
            School identity, public contact, scale estimates, languages, and campuses stay editable
            by authorized leadership. Every save is scoped, version-protected, and audited.
          </p>
        </div>
        <aside>
          <SettingsIcon />
          <span>Current school</span>
          <strong>{data.displayName}</strong>
          <small>
            {data.schoolSlug} · {data.schoolStatus.toLowerCase()}
          </small>
        </aside>
      </header>

      <nav className="school-config-tabs" aria-label="School setup sections">
        <button
          type="button"
          className={tab === "identity" ? "is-active" : ""}
          aria-current={tab === "identity" ? "page" : undefined}
          onClick={() => setTab("identity")}
        >
          <span>01</span> Identity & public facts
        </button>
        <button
          type="button"
          className={tab === "campuses" ? "is-active" : ""}
          aria-current={tab === "campuses" ? "page" : undefined}
          onClick={() => setTab("campuses")}
        >
          <span>02</span> Campuses
        </button>
        <button
          type="button"
          className={tab === "launch" ? "is-active" : ""}
          aria-current={tab === "launch" ? "page" : undefined}
          onClick={() => setTab("launch")}
        >
          <span>03</span> Launch readiness
        </button>
      </nav>

      {error && (
        <p className="school-config-notice school-config-notice--error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="school-config-notice" role="status">
          <Check /> {notice}
        </p>
      )}

      {tab === "identity" && (
        <div className="school-config-identity">
          <aside className="school-config-summary">
            <span>Verification boundary</span>
            <h2>Only publish facts the school confirms.</h2>
            <p>
              Saving refreshes the verification timestamp. Affiliation numbers, facilities, results,
              and awards remain absent until the authority supplies and confirms them through their
              dedicated workflows.
            </p>
            <div>
              <small>Last verified</small>
              <strong>{dateLabel(data.verifiedAt)}</strong>
            </div>
            <ul>
              <li>
                <Check /> Explicit school scope
              </li>
              <li>
                <Check /> Conflict-safe version
              </li>
              <li>
                <Check /> Required change reason
              </li>
            </ul>
          </aside>

          <form key={data.rowVersion} className="school-config-form" onSubmit={saveProfile}>
            <header>
              <div>
                <span>Verified school record</span>
                <h2>Identity and operating profile</h2>
              </div>
              <small>Version {data.rowVersion}</small>
            </header>
            <fieldset>
              <legend>School identity</legend>
              <label>
                <span>Legal name</span>
                <input name="legalName" defaultValue={data.legalName} maxLength={200} required />
              </label>
              <label>
                <span>Display name</span>
                <input
                  name="displayName"
                  defaultValue={data.displayName}
                  maxLength={160}
                  required
                />
              </label>
              <label>
                <span>Education board</span>
                <input name="boardCode" defaultValue={data.boardCode} maxLength={40} required />
              </label>
              <label>
                <span>Grade range</span>
                <input name="gradeRange" defaultValue={data.gradeRange} maxLength={80} required />
              </label>
              <label className="school-config-wide">
                <span>Motto</span>
                <input name="motto" defaultValue={data.motto || ""} maxLength={160} />
              </label>
              <label className="school-config-wide">
                <span>Supported language tags</span>
                <input
                  name="supportedLanguages"
                  defaultValue={data.supportedLanguages.join(", ")}
                  placeholder="hi-IN, en-IN"
                  required
                />
                <small>Comma-separated BCP 47 tags; change these as school support changes.</small>
              </label>
            </fieldset>

            <fieldset>
              <legend>Public contact and location</legend>
              <label>
                <span>Public email</span>
                <input
                  name="publicEmail"
                  type="email"
                  defaultValue={data.publicEmail || ""}
                  maxLength={254}
                />
              </label>
              <label>
                <span>Public phone</span>
                <input
                  name="publicPhone"
                  type="tel"
                  defaultValue={data.publicPhone || ""}
                  maxLength={30}
                />
              </label>
              <label className="school-config-wide">
                <span>Principal / public contact name</span>
                <input
                  name="principalDisplayName"
                  defaultValue={data.principalDisplayName || ""}
                  maxLength={120}
                />
              </label>
              <label className="school-config-wide">
                <span>Address line</span>
                <input
                  name="addressLine"
                  defaultValue={data.addressLine}
                  maxLength={240}
                  required
                />
              </label>
              <label>
                <span>Locality / market</span>
                <input name="locality" defaultValue={data.locality || ""} maxLength={120} />
              </label>
              <label>
                <span>City</span>
                <input name="city" defaultValue={data.city} maxLength={120} required />
              </label>
              <label>
                <span>State</span>
                <input name="stateName" defaultValue={data.stateName} maxLength={120} required />
              </label>
              <label>
                <span>Country code</span>
                <input
                  name="countryCode"
                  defaultValue={data.countryCode}
                  pattern="[A-Za-z]{2}"
                  maxLength={2}
                  required
                />
              </label>
            </fieldset>

            <fieldset>
              <legend>Operational defaults and estimates</legend>
              <label>
                <span>Time zone</span>
                <input name="timeZone" defaultValue={data.timeZone} maxLength={60} required />
              </label>
              <label>
                <span>Currency code</span>
                <input
                  name="currencyCode"
                  defaultValue={data.currencyCode}
                  pattern="[A-Za-z]{3}"
                  maxLength={3}
                  required
                />
              </label>
              <label>
                <span>Learners — minimum estimate</span>
                <input
                  name="learnerCountMin"
                  type="number"
                  min="0"
                  defaultValue={data.learnerCountMin ?? ""}
                />
              </label>
              <label>
                <span>Learners — maximum estimate</span>
                <input
                  name="learnerCountMax"
                  type="number"
                  min="0"
                  defaultValue={data.learnerCountMax ?? ""}
                />
              </label>
              <label>
                <span>Teaching staff estimate</span>
                <input
                  name="teachingStaffEstimate"
                  type="number"
                  min="0"
                  defaultValue={data.teachingStaffEstimate ?? ""}
                />
              </label>
              <label>
                <span>Other staff estimate</span>
                <input
                  name="operationsStaffEstimate"
                  type="number"
                  min="0"
                  defaultValue={data.operationsStaffEstimate ?? ""}
                />
              </label>
            </fieldset>

            <footer>
              <label className="school-config-confirm">
                <input name="accuracyConfirmed" type="checkbox" required />
                <span>
                  I confirm these school facts are accurate and approved for this operating profile.
                </span>
              </label>
              <label>
                <span>Reason for this change</span>
                <input
                  name="reason"
                  defaultValue="School profile reviewed and confirmed by authorized school leadership"
                  maxLength={500}
                  required
                />
              </label>
              <button className="button button--dark" disabled={busy}>
                {busy ? "Saving protected update…" : "Verify and save profile"} <ArrowUpRight />
              </button>
            </footer>
          </form>
        </div>
      )}

      {tab === "campuses" && (
        <div className="school-config-campus-layout">
          <section className="school-config-campus-list">
            <header>
              <div>
                <span>School-scoped locations</span>
                <h2>
                  {data.campuses.length} configured campus{data.campuses.length === 1 ? "" : "es"}
                </h2>
              </div>
              <button type="button" onClick={() => setSelectedCampusId("")}>
                Add campus
              </button>
            </header>
            <div>
              {data.campuses.map((campus) => (
                <button
                  type="button"
                  key={campus.id}
                  className={selectedCampusId === campus.id ? "is-selected" : ""}
                  onClick={() => setSelectedCampusId(campus.id)}
                >
                  <span>
                    <PeopleIcon />
                  </span>
                  <span>
                    <strong>{campus.displayName}</strong>
                    <small>
                      {campus.code} · {campus.timeZone}
                    </small>
                  </span>
                  <em data-status={campus.status}>{campus.status.toLowerCase()}</em>
                </button>
              ))}
            </div>
          </section>

          <form
            key={selectedCampus?.id || "new-campus"}
            className="school-config-campus-form"
            onSubmit={saveCampus}
          >
            <header>
              <SettingsIcon />
              <div>
                <span>{selectedCampus ? "Protected update" : "New school location"}</span>
                <h2>
                  {selectedCampus ? `Edit ${selectedCampus.displayName}` : "Configure a campus"}
                </h2>
              </div>
            </header>
            <p>
              Campuses remain configuration, not hard-coded application choices. At least one campus
              must stay active so admissions and school operations keep a valid location.
            </p>
            <label>
              <span>Campus code</span>
              <input
                name="code"
                defaultValue={selectedCampus?.code || ""}
                pattern="[A-Za-z0-9][A-Za-z0-9_-]{1,29}"
                maxLength={30}
                required
              />
            </label>
            <label>
              <span>Display name</span>
              <input
                name="displayName"
                defaultValue={selectedCampus?.displayName || ""}
                maxLength={160}
                required
              />
            </label>
            <label>
              <span>Time zone</span>
              <input
                name="timeZone"
                defaultValue={selectedCampus?.timeZone || data.timeZone}
                maxLength={60}
                required
              />
            </label>
            <label>
              <span>Status</span>
              <select name="status" defaultValue={selectedCampus?.status || "ACTIVE"}>
                <option value="ACTIVE">Active</option>
                <option value="INACTIVE">Inactive</option>
              </select>
            </label>
            <label>
              <span>Reason for this change</span>
              <textarea
                name="reason"
                defaultValue={
                  selectedCampus
                    ? "Campus configuration reviewed by authorized school leadership"
                    : "Campus created from an approved school location record"
                }
                maxLength={500}
                required
              />
            </label>
            <footer>
              {selectedCampus && (
                <button type="button" onClick={() => setSelectedCampusId("")}>
                  Cancel edit
                </button>
              )}
              <button className="button button--dark" disabled={busy}>
                {busy ? "Saving…" : selectedCampus ? "Save campus" : "Create campus"}{" "}
                <ArrowUpRight />
              </button>
            </footer>
          </form>
        </div>
      )}

      {tab === "launch" && <LaunchReadinessWorkspace schoolId={data.schoolId} />}
    </section>
  );
}
