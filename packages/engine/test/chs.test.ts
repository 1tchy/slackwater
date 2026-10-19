import { describe, expect, test } from "vitest";
import recipe from "../../../fixtures/chs-tide-fit.json";
import parityInput from "../../../fixtures/harmonic-fit.json";
import { chsTideFit, constituents } from "../src/index.js";

describe("chsTideFit", () => {
  test("matches the shared recipe fixture", () => {
    expect(chsTideFit.basis).toEqual(recipe.basis);
    expect(chsTideFit.days).toBe(recipe.days);
    expect(chsTideFit.sampleMinutes).toBe(recipe.sampleMinutes);
  });

  test("is the basis the fit parity fixtures exercise", () => {
    expect(parityInput.basis).toEqual(recipe.basis);
  });

  test("names only constituents the engine knows", () => {
    for (const name of chsTideFit.basis) expect(constituents[name], name).toBeDefined();
  });
});
