import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { calculateSimulator, DEFAULTS } from "../../01-售前阶段/客户演示交付包/劳务保理专项资金与多方收益模拟器-v1.mjs";

const closeTo = (actual, expected, tolerance = 0.01) => {
  assert.ok(Math.abs(actual - expected) <= tolerance, `expected ${actual} to be within ${tolerance} of ${expected}`);
};

test("default scenario uses one monthly financing batch and a twelve-month cumulative view", () => {
  const result = calculateSimulator();
  assert.equal(result.monthly.financedPrincipal, 8_000_000);
  assert.equal(result.annual.financedPrincipal, 96_000_000);
  closeTo(result.monthly.externalCost, 98_630.1369863);
  closeTo(result.monthly.specialTotalCost, 69_095.8904109);
  closeTo(result.monthly.quotedCostAdvantage, 29_534.2465753);
  closeTo(result.annual.externalCost, result.monthly.externalCost * 12);
  closeTo(result.annual.specialTotalCost, result.monthly.specialTotalCost * 12);
  closeTo(result.annual.quotedCostAdvantage, result.monthly.quotedCostAdvantage * 12);
  assert.equal(result.averageOutstandingPrincipal, 24_000_000);
  assert.ok(result.monthly.supplierFinalProfit > 0);
  assert.ok(result.monthly.supplierIncrementalBenefit > 0);
  assert.equal(result.economicFeasible, true);
});

test("external 4%, 5% and 6% scenarios change the cost advantage without changing the special model", () => {
  const result = calculateSimulator();
  assert.deepEqual(result.sensitivity.map((item) => item.annualRate), [0.04, 0.05, 0.06]);
  assert.ok(result.sensitivity[0].monthlyQuotedCostAdvantage < result.sensitivity[1].monthlyQuotedCostAdvantage);
  assert.ok(result.sensitivity[1].monthlyQuotedCostAdvantage < result.sensitivity[2].monthlyQuotedCostAdvantage);
  assert.ok(result.sensitivity.every((item) => Math.abs(item.annualQuotedCostAdvantage - item.monthlyQuotedCostAdvantage * 12) < 0.01));
  assert.ok(result.sensitivity.every((item) => item.monthlyExternalCost > 0));
});

test("annual cumulative values follow the selected project months", () => {
  const result = calculateSimulator({ projectMonths: 6 });
  for (const key of ["externalCost", "specialTotalCost", "quotedCostAdvantage", "supplierFinalProfit", "employerNetBenefit", "fundingSourceNetBenefit", "factoringNetBenefit", "platformNetBenefit"]) {
    closeTo(result.annual[key], result.monthly[key] * 6);
  }
  for (const item of result.sensitivity) {
    closeTo(item.annualExternalCost, item.monthlyExternalCost * 6);
    closeTo(item.annualQuotedCostAdvantage, item.monthlyQuotedCostAdvantage * 6);
  }
});

test("founder funding keeps the founder return separate from employer operating benefit", () => {
  const company = calculateSimulator({ fundingSourceType: "employer-company" });
  const founder = calculateSimulator({ fundingSourceType: "founder-individual" });
  assert.equal(company.monthly.employerNetBenefit, company.monthly.employerOperatingNetBenefit + company.monthly.fundingSourceNetBenefit);
  assert.equal(founder.monthly.employerNetBenefit, founder.monthly.employerOperatingNetBenefit);
  assert.equal(founder.monthly.fundingSourceNetBenefit, company.monthly.fundingSourceNetBenefit);
  assert.equal(company.annual.employerNetBenefit, company.monthly.employerNetBenefit * 12);
  assert.equal(founder.annual.fundingSourceNetBenefit, founder.monthly.fundingSourceNetBenefit * 12);
});

test("fee payer and risk allocation are visible in supplier and party results", () => {
  const result = calculateSimulator({ supplierCommercialCostShare: 0.5 });
  assert.ok(result.monthly.employerCommercialSubsidy > 0);
  assert.equal(result.monthly.supplierCommercialBurden + result.monthly.employerCommercialSubsidy, result.monthly.commercialCharges);
  const riskTotal = Object.values(result.monthly.riskLoss).reduce((sum, value) => sum + value, 0);
  closeTo(riskTotal, result.monthly.expectedRiskLoss);
  for (const party of Object.keys(result.monthly.riskLoss)) {
    closeTo(result.annual.riskLoss[party], result.monthly.riskLoss[party] * 12);
  }
  assert.throws(() => calculateSimulator({ riskSharePlatform: 0.1 }), RangeError);
});

test("a negative spread or negative supplier result fails the economic gate", () => {
  const expensive = calculateSimulator({ externalAnnualRate: 0.02, funderReturnRate: 0.05, monthlySupplierProfitBeforeFinancing: 10_000 });
  assert.ok(expensive.monthly.quotedCostAdvantage < 0);
  assert.ok(expensive.monthly.supplierFinalProfit < 0);
  assert.equal(expensive.economicFeasible, false);
  assert.throws(() => calculateSimulator({ tenorDays: 0 }), RangeError);
  assert.throws(() => calculateSimulator({ advanceRatio: 1.1 }), RangeError);
  assert.throws(() => calculateSimulator({ externalAnnualRate: "" }), RangeError);
});

test("standalone HTML uses every editable condition and stays consistent with the calculation module", () => {
  const html = readFileSync(new URL("../../01-售前阶段/客户演示交付包/劳务保理专项资金与多方收益模拟器-v1.html", import.meta.url), "utf8");
  const inputSection = html.match(/<section aria-labelledby="inputsTitle">([\s\S]*?)<\/section>/)[1];
  const allEditableIds = [...html.matchAll(/<(?:input|select)\b[^>]*id="([^"]+)"/g)].map((match) => match[1]).sort();
  const sectionEditableIds = [...inputSection.matchAll(/<(?:input|select)\b[^>]*id="([^"]+)"/g)].map((match) => match[1]).sort();
  assert.deepEqual(allEditableIds, sectionEditableIds, "only project conditions may contain editable parameters");
  assert.deepEqual(allEditableIds, Object.keys(DEFAULTS).sort(), "every editable parameter must enter the calculation inputs");
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], {
    id: match[1], value: "", textContent: "", innerHTML: "", hidden: false, handlers: {},
    addEventListener(type, handler) { this.handlers[type] = handler; },
    setAttribute() {}, removeAttribute() {},
  }]));
  const context = vm.createContext({
    Intl, console,
    document: { getElementById(id) { assert.ok(elements.has(id), `missing page element: ${id}`); return elements.get(id); } },
    window: { print() {} },
  });
  vm.runInContext(html.match(/<script[^>]*>([\s\S]*?)<\/script>/)[1], context);
  const pageResult = vm.runInContext("calculateSimulator(readInputs())", context);
  const moduleResult = calculateSimulator();
  for (const scope of ["monthly", "annual"]) {
    for (const key of ["financedPrincipal", "externalCost", "specialTotalCost", "quotedCostAdvantage", "supplierFinalProfit", "employerNetBenefit", "fundingSourceNetBenefit", "factoringNetBenefit", "platformNetBenefit"]) {
      closeTo(pageResult[scope][key], moduleResult[scope][key]);
    }
  }
  assert.equal(elements.get("errorMessage").textContent, "");
  assert.match(elements.get("benefitRows").innerHTML, /劳务供应商/);
  assert.match(elements.get("sensitivityRows").innerHTML, /4%/);
  elements.get("riskSharePlatform").value = "10";
  elements.get("riskSharePlatform").handlers.input();
  assert.match(elements.get("errorMessage").textContent, /风险承担比例/);
  assert.equal(elements.get("results").hidden, true);
});
