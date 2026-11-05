import { readFile } from "node:fs/promises";
import process from "node:process";
import yaml from "js-yaml";

const contractPath = new URL("../packages/contracts/openapi.yaml", import.meta.url);

try {
  const source = await readFile(contractPath, "utf8");
  const contract = yaml.load(source, { filename: "packages/contracts/openapi.yaml" });
  if (!contract || typeof contract !== "object"
      || !contract.openapi || !contract.info || !contract.paths || !contract.components) {
    throw new Error("The document is missing required OpenAPI root fields.");
  }
  console.log("OpenAPI contract parsed with unique mapping keys.");
} catch (error) {
  console.error(error instanceof Error ? error.message : error);
  process.exitCode = 1;
}
