/** Minimal shared projection of the FamilyChildSummary API contract. */
export type FamilyChild = {
  learnerId: string;
  legalName: string;
  gradeCode: string;
  sectionName: string | null;
};

export function familyChildren(payload: unknown): FamilyChild[] {
  const children = payload && typeof payload === "object" && "children" in payload ? payload.children : null;
  if (!Array.isArray(children) || !children.every((child: unknown) => {
    if (!child || typeof child !== "object") return false;
    const value = child as Record<string, unknown>;
    return typeof value.learnerId === "string" && /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(value.learnerId)
      && typeof value.legalName === "string" && typeof value.gradeCode === "string"
      && (value.sectionName === null || typeof value.sectionName === "string");
  })) throw new Error("Linked children are unavailable. Please contact the school.");
  return children.map(({ learnerId, legalName, gradeCode, sectionName }) => ({ learnerId, legalName, gradeCode, sectionName }));
}
