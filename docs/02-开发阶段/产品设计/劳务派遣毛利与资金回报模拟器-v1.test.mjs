import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";
import { calculateSimulator, DEFAULTS } from "../../01-售前阶段/客户演示交付包/劳务派遣毛利与资金回报模拟器-v1.mjs";

const closeTo = (actual, expected, tolerance = 0.01) => {
  assert.ok(Math.abs(actual - expected) <= tolerance, `expected ${actual} to be within ${tolerance} of ${expected}`);
};

test("annual wages remain twelve months while 90-day wages revolve on three months of capital", () => {
  const result = calculateSimulator({ clientPaymentDays: 90, annualFinancingRate: 0.04 });
  assert.equal(result.beforeFee.monthlyWages * 12, 82368000);
  assert.equal(result.beforeFee.wageWorkingCapital, result.beforeFee.monthlyWages * 3);
  assert.equal(result.beforeFee.wageWorkingCapital, result.afterFee.wageWorkingCapital);
  for (const scenario of [result.beforeFee, result.afterFee]) {
    closeTo(scenario.annualOperatingCapitalReturn, scenario.annualOperatingProfit / scenario.initialWorkingCapital);
    closeTo(scenario.annualNetOperatingCapitalReturn, scenario.annualOperatingProfitAfterFinancing / scenario.initialWorkingCapital);
    closeTo(scenario.annualOperatingReturnSpread, scenario.annualOperatingCapitalReturn - 0.04);
    closeTo(scenario.annualOperatingReturnSpread, scenario.annualNetOperatingCapitalReturn);
    assert.ok(scenario.initialWorkingCapital >= scenario.wageWorkingCapital);
  }
  const zeroCapital = calculateSimulator({ clientPaymentDays: 0, monthlyFixedCost: 0 });
  assert.equal(zeroCapital.beforeFee.annualOperatingCapitalReturn, null);
  assert.equal(zeroCapital.beforeFee.annualNetOperatingCapitalReturn, null);
  const loss = calculateSimulator({ hiringChannelCostPerWorkerHour: 2 });
  assert.ok(loss.afterFee.annualOperatingProfitAfterFinancing < 0);
  assert.ok(loss.afterFee.annualNetOperatingCapitalReturn < 0);
});

test("standalone page renders all three ledgers and stays consistent with the calculation module", () => {
  const html = readFileSync(new URL("../../01-售前阶段/客户演示交付包/劳务派遣毛利与资金回报模拟器-v1.html", import.meta.url), "utf8");
  const inputSection = html.match(/<section aria-labelledby="inputsTitle">([\s\S]*?)<\/section>/)[1];
  const allEditableIds = [...html.matchAll(/<(?:input|select)\b[^>]*id="([^"]+)"/g)].map((match) => match[1]).sort();
  const sectionEditableIds = [...inputSection.matchAll(/<(?:input|select)\b[^>]*id="([^"]+)"/g)].map((match) => match[1]).sort();
  assert.deepEqual(allEditableIds, sectionEditableIds, "only project conditions may contain editable parameters");
  assert.deepEqual(allEditableIds, Object.keys(DEFAULTS).sort(), "every editable parameter must enter the calculation inputs");
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], {
    id: match[1], value: "", textContent: "", innerHTML: "", clientWidth: 900, handlers: {}, labels: [{ textContent: match[1] }],
    classList: { toggle() {} },
    addEventListener(type, handler) { this.handlers[type] = handler; },
    add() {}, replaceChildren() {}, setAttribute() {}, removeAttribute() {},
  }]));
  const context = vm.createContext({
    Intl, console, Option: function (text, value) { this.text = text; this.value = value; },
    document: { getElementById(id) { assert.ok(elements.has(id), `missing page element: ${id}`); return elements.get(id); } },
    window: { addEventListener() {}, print() {} },
  });
  vm.runInContext(html.match(/<script[^>]*>([\s\S]*?)<\/script>/)[1], context);
  const element = (id) => elements.get(id);
  const monthlySection = html.match(/<section aria-labelledby="monthlyTitle">([\s\S]*?)<\/section>/)[1];
  const monthlyHeaders = [...monthlySection.matchAll(/<th>(.*?)<\/th>/g)].map((match) => match[1]);
  assert.equal(monthlyHeaders.length, 14);
  assert.equal(monthlyHeaders[5], "固定运营费用（元/月）");
  assert.equal(monthlyHeaders[9], "经营现金流");
  assert.equal(element("hiringChannelCostPerWorkerHour").value, "1.5");
  assert.equal(element("platformFeePerPersonDay").value, "1");
  const assertRendered = () => {
    assert.equal(element("errorMessage").textContent, "");
    for (const id of ["businessRows", "monthlyComparisonRows", "annualComparisonRows", "capitalComparisonRows"]) {
      assert.match(element(id).innerHTML, /ledger-row/);
      assert.doesNotMatch(element(id).innerHTML, /NaN|Infinity|undefined/);
    }
    assert.equal((element("monthlyRows").innerHTML.match(/<tr>/g) || []).length, 24);
  };
  for (const input of [{}, { workers: 500, clientPaymentDays: 90, annualFinancingRate: 0.04 }, { hiringChannelCostPerWorkerHour: 2 }, { clientPaymentDays: 0, monthlyFixedCost: 0 }]) {
    context.testInput = input;
    const pageResult = vm.runInContext("calculateSimulator(testInput)", context);
    const moduleResult = calculateSimulator(input);
    for (const scenario of ["beforeFee", "afterFee"]) {
      for (const key of ["monthlyRevenue", "monthlyWages", "monthlyHiringChannelCost", "annualOperatingProfitAfterFinancing", "initialWorkingCapital", "wageWorkingCapital", "annualOperatingCapitalReturn", "annualNetOperatingCapitalReturn"]) {
        assert.equal(pageResult[scenario][key], moduleResult[scenario][key], `${scenario}.${key}`);
      }
      for (let index = 0; index < 24; index += 1) {
        assert.equal(pageResult[scenario].rows[index].fixedOperatingCost, moduleResult[scenario].rows[index].fixedOperatingCost);
      }
    }
    vm.runInContext("renderOverview(calculateSimulator(testInput)); renderCompare(calculateSimulator(testInput)); renderMonthly(calculateSimulator(testInput));", context);
    const renderedRows = [...element("monthlyRows").innerHTML.matchAll(/<tr>([\s\S]*?)<\/tr>/g)];
    for (const [index, renderedRow] of renderedRows.entries()) {
      const cells = [...renderedRow[1].matchAll(/<td([^>]*)>(.*?)<\/td>/g)];
      const expectedRow = moduleResult.afterFee.rows[index];
      const formatMoney = (value) => `¥${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(value)}`;
      assert.equal(cells.length, monthlyHeaders.length);
      assert.equal(cells[5][2], formatMoney(expectedRow.fixedOperatingCost));
      assert.equal(cells[9][2], formatMoney(expectedRow.operatingCashFlow));
      assert.match(cells[9][1], new RegExp(vm.runInContext(`signedClass(${expectedRow.operatingCashFlow})`, context)));
    }
    assertRendered();
  }
  element("workers").value = "500";
  element("workers").handlers.change();
  assert.equal(element("monthlyFixedCost").value, "30000");
  assertRendered();
  element("resetButton").handlers.click();
  assert.equal(element("monthlyFixedCost").value, "60000");
  assert.equal(element("hiringChannelCostPerWorkerHour").value, "1.5");
  assert.equal(element("platformFeePerPersonDay").value, "1");
  assertRendered();
  const snapshot = () => [...elements.values()].map((item) => item.innerHTML + item.textContent).join("\n");
  const baseline = snapshot();
  const changes = { workers: 500, workDaysPerMonth: 24, hoursPerDay: 10, wagePaymentDays: 60, clientHourlyRate: 25, workerHourlyRate: 23, hiringChannelCostPerWorkerHour: 0.5, monthlyFixedCost: 10000, accidentInsurancePerWorkerMonth: 80, clientPaymentDays: 90, annualFinancingRate: 4, platformFeePerPersonDay: 2.5 };
  assert.deepEqual(Object.keys(changes).sort(), Object.keys(DEFAULTS).sort(), "every project input needs a dependency audit");
  const formattedMoney = (value) => `¥${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(value)}`;
  for (const [id, value] of Object.entries(changes)) {
    element("resetButton").handlers.click();
    element(id).value = String(value);
    element(id).handlers.change();
    assertRendered();
    assert.notEqual(snapshot(), baseline, `${id} must change derived results`);
    const input = JSON.parse(vm.runInContext("JSON.stringify(readInputs())", context));
    const actual = vm.runInContext("calculateSimulator(readInputs())", context);
    const expected = calculateSimulator(input);
    for (const scenario of ["beforeFee", "afterFee"]) {
      for (const [key, value] of Object.entries(expected[scenario])) {
        if (typeof value === "number" || value === null) assert.equal(actual[scenario][key], value, `${id}: ${scenario}.${key}`);
      }
    }
    for (const key of ["monthlyRevenue", "monthlyWages", "monthlyHiringChannelCost", "monthlyGrossProfit"]) {
      assert.ok(element("businessRows").innerHTML.includes(formattedMoney(expected.beforeFee[key])), `${id}: rendered ${key}`);
      assert.ok(element("businessRows").innerHTML.includes(formattedMoney(expected.beforeFee[key] * 12)), `${id}: rendered annual ${key}`);
    }
    assert.ok(element("comparisonNote").textContent.includes(formattedMoney(expected.annualFee)), `${id}: rendered annual fee`);
    assert.ok(element("annualComparisonRows").innerHTML.includes(formattedMoney(expected.beforeFee.annualOperatingCost)), `${id}: annual cost is rendered`);
    assert.match(element("annualComparisonRows").innerHTML, /年度成本利润率/);
    assert.match(element("capitalComparisonRows").innerHTML, /年经营结余 ÷ 项目总周转本金/);
    assert.ok(element("capitalFormula").textContent.includes(formattedMoney(expected.beforeFee.wageWorkingCapital)), `${id}: rendered wage capital`);
    assert.equal((element("monthlyRows").innerHTML.match(/<tr>/g) || []).length, 24);
    assert.ok(element("beforeYearTable").innerHTML.includes(formattedMoney(expected.beforeFee.years[0].customerCollections)));
    assert.ok(element("afterYearTable").innerHTML.includes(formattedMoney(expected.afterFee.years[1].endingCashBalance)));
  }
  element("resetButton").handlers.click();
  element("accidentInsurancePerWorkerMonth").value = "80";
  element("accidentInsurancePerWorkerMonth").handlers.input();
  assert.equal(element("monthlyFixedCost").value, "80000");
  element("monthlyFixedCost").value = "1234";
  element("monthlyFixedCost").handlers.input();
  element("accidentInsurancePerWorkerMonth").value = "90";
  element("accidentInsurancePerWorkerMonth").handlers.input();
  assert.equal(element("monthlyFixedCost").value, "1234", "manual total overrides insurance-based default");
  element("clientPaymentDays").value = "90";
  element("clientPaymentDays").handlers.change();
  assert.match(element("collectionTimingExplanation").textContent, /第一年实际非零回款9笔/);
  assert.doesNotMatch(element("capitalFormula").textContent, /月工资×3/);
  element("hoursPerDay").value = "";
  element("hoursPerDay").handlers.input();
  assert.ok(element("errorMessage").textContent);
  for (const id of ["businessRows", "monthlyComparisonRows", "annualComparisonRows", "capitalComparisonRows", "beforeYearTable", "afterYearTable", "monthlyRows", "cashChart"]) assert.equal(element(id).innerHTML, "", `${id} must not retain stale values after invalid input`);
  element("resetButton").handlers.click();
  assertRendered();
});

test("per-worker gross economics without channel costs match the original acceptance baseline", () => {
  const result = calculateSimulator({ workers: 1, monthlyFixedCost: 0, hiringChannelCostPerWorkerHour: 0 });
  const { beforeFee } = result;
  assert.equal(beforeFee.monthlyHours, 312);
  assert.equal(beforeFee.monthlyRevenue, 7488);
  assert.equal(beforeFee.monthlyWages, 6864);
  assert.equal(beforeFee.monthlyGrossProfit, 624);
  closeTo(beforeFee.grossMargin, 0.0833333333);
  assert.equal(beforeFee.annualGrossProfit, 7488);
  assert.equal(beforeFee.initialWorkingCapital, 13728);
  closeTo(beforeFee.annualGrossProfitCapitalReturn, 0.5454545454);
});

test("1000-worker baseline simulates startup and steady-state cash flows", () => {
  const result = calculateSimulator({ monthlyFixedCost: 0, hiringChannelCostPerWorkerHour: 0 });
  const before = result.beforeFee;
  assert.equal(result.input.workers, DEFAULTS.workers);
  assert.equal(before.initialWorkingCapital, 13728000);
  assert.equal(before.annualGrossProfit, 7488000);
  closeTo(before.annualGrossProfitCapitalReturn, 0.5454545454);
  assert.equal(before.years[0].revenue, 89856000);
  assert.equal(before.years[0].grossProfit, 7488000);
  assert.equal(before.years[0].customerCollections, 74880000);
  assert.equal(before.years[0].wagePayments, 82368000);
  assert.equal(before.years[0].operatingCashFlow, -7488000);
  assert.equal(before.years[0].receivables, 14976000);
  assert.equal(before.years[1].customerCollections, 89856000);
  assert.equal(before.years[1].operatingCashFlow, 7488000);
  assert.equal(before.years[1].receivables, 14976000);
  assert.equal(before.rows.length, 24);
  assert.equal(before.rows[0].customerCollection, 0);
  assert.equal(before.rows[1].customerCollection, 0);
  assert.equal(before.rows[2].customerCollection, before.monthlyRevenue);
  assert.equal(before.rows[0].initialCapitalCashBalance, 6864000);
  assert.equal(before.rows[1].initialCapitalCashBalance, 0);
  assert.equal(before.rows.every((row) => row.initialCapitalCashBalance >= -0.001), true);
});

test("platform fee is separated from gross profit and changes cash need and return", () => {
  const result = calculateSimulator({ platformFeePerPersonDay: 0.5, monthlyFixedCost: 0, hiringChannelCostPerWorkerHour: 0 });
  const { beforeFee, afterFee } = result;
  assert.equal(beforeFee.monthlyGrossProfit, afterFee.monthlyGrossProfit);
  assert.equal(afterFee.monthlyPlatformFee, 13000);
  assert.equal(result.annualFee, 156000);
  assert.equal(afterFee.annualProfitAfterPlatformFee, 7332000);
  assert.equal(afterFee.initialWorkingCapital, 13754000);
  closeTo(afterFee.annualAfterFeeCapitalReturn, 7332000 / 13754000);
  assert.equal(afterFee.years[0].receivables, beforeFee.years[0].receivables);
  assert.equal(afterFee.years[0].operatingCashFlow, beforeFee.years[0].operatingCashFlow - result.annualFee);
  assert.equal(afterFee.rows[0].platformFee, 13000);
});

test("annualized capital return is compared with financing rate before and after platform fees", () => {
  const result = calculateSimulator({ annualFinancingRate: 0.04, platformFeePerPersonDay: 5 });
  closeTo(result.beforeFee.annualGrossReturnSpread, result.beforeFee.annualGrossProfitCapitalReturn - 0.04);
  closeTo(result.afterFee.annualAfterFeeReturnSpread, result.afterFee.annualAfterFeeCapitalReturn - 0.04);
  closeTo(result.beforeFee.annualGrossReturnAfterFinancing, result.beforeFee.annualGrossProfitCapitalReturn - 0.04);
  closeTo(result.afterFee.annualAfterFeeReturnAfterFinancing, result.afterFee.annualAfterFeeCapitalReturn - 0.04);
});

test("longer terms increase modeled working capital and late receivables", () => {
  const baseline = calculateSimulator();
  const longerTerm = calculateSimulator({ clientPaymentDays: 90 });
  assert.ok(longerTerm.beforeFee.initialWorkingCapital > baseline.beforeFee.initialWorkingCapital);
  assert.equal(longerTerm.beforeFee.rows[3].customerCollection, longerTerm.beforeFee.monthlyRevenue);
  assert.equal(longerTerm.beforeFee.years[0].customerCollections, longerTerm.beforeFee.monthlyRevenue * 9);
  assert.equal(longerTerm.beforeFee.years[0].receivables, longerTerm.beforeFee.monthlyRevenue * 3);
});

test("wage payment cycle uses 30-day tiers and shifts cash payments without shifting gross profit", () => {
  const sameMonth = calculateSimulator({ wagePaymentDays: 30 });
  const nextMonth = calculateSimulator({ wagePaymentDays: 60 });
  const sixMonth = calculateSimulator({ wagePaymentDays: 180 });
  assert.equal(sameMonth.beforeFee.rows[0].wagePayment, sameMonth.beforeFee.monthlyWages);
  assert.equal(nextMonth.beforeFee.rows[0].wagePayment, 0);
  assert.equal(nextMonth.beforeFee.rows[1].wagePayment, nextMonth.beforeFee.monthlyWages);
  assert.equal(nextMonth.beforeFee.rows[0].wagesPayable, nextMonth.beforeFee.monthlyWages);
  assert.equal(nextMonth.beforeFee.rows[1].wagesPayable, nextMonth.beforeFee.monthlyWages);
  assert.equal(nextMonth.beforeFee.years[0].wagePayments, nextMonth.beforeFee.monthlyWages * 11);
  assert.equal(nextMonth.beforeFee.years[0].wagesPayable, nextMonth.beforeFee.monthlyWages);
  assert.equal(nextMonth.beforeFee.rows[0].grossProfit, sameMonth.beforeFee.rows[0].grossProfit);
  assert.ok(nextMonth.beforeFee.initialWorkingCapital < sameMonth.beforeFee.initialWorkingCapital);
  assert.equal(sixMonth.beforeFee.rows[5].wagePayment, sixMonth.beforeFee.monthlyWages);
  assert.throws(() => calculateSimulator({ wagePaymentDays: 45 }), RangeError);
});

test("hiring channel cost is hourly, reduces project gross profit, and scales with hours", () => {
  const baseline = calculateSimulator({ hiringChannelCostPerWorkerHour: 0 });
  const narrowerSpread = calculateSimulator({ clientHourlyRate: 23, hiringChannelCostPerWorkerHour: 0 });
  const channelCost = calculateSimulator({ hiringChannelCostPerWorkerHour: 0.5 });
  const doubleScale = calculateSimulator({ workers: 2000, hiringChannelCostPerWorkerHour: 0 });
  assert.ok(narrowerSpread.beforeFee.monthlyGrossProfit < baseline.beforeFee.monthlyGrossProfit);
  assert.equal(channelCost.beforeFee.monthlyHiringChannelCost, 156000);
  assert.equal(channelCost.beforeFee.monthlyGrossProfit, baseline.beforeFee.monthlyGrossProfit - 156000);
  assert.equal(channelCost.beforeFee.rows[0].hiringChannelCost, 156000);
  assert.equal(channelCost.beforeFee.rows[0].operatingCashFlow, baseline.beforeFee.rows[0].operatingCashFlow - 156000);
  assert.equal(channelCost.beforeFee.monthlyGrossProfit / (1000 * 312), 1.5);
  assert.equal(doubleScale.beforeFee.monthlyRevenue, baseline.beforeFee.monthlyRevenue * 2);
  assert.equal(doubleScale.beforeFee.initialWorkingCapital, baseline.beforeFee.initialWorkingCapital * 2);
});

test("hiring channel cost must use 0.1 increments and stay within the hourly price spread", () => {
  assert.equal(calculateSimulator({ clientHourlyRate: 24, workerHourlyRate: 22, hiringChannelCostPerWorkerHour: 2 }).beforeFee.monthlyGrossProfit, 0);
  assert.throws(() => calculateSimulator({ clientHourlyRate: 24, workerHourlyRate: 22, hiringChannelCostPerWorkerHour: 2.1 }), RangeError);
  assert.throws(() => calculateSimulator({ clientHourlyRate: 24, workerHourlyRate: 22, hiringChannelCostPerWorkerHour: 0.15 }), RangeError);
  assert.throws(() => calculateSimulator({ clientHourlyRate: 22, workerHourlyRate: 22, hiringChannelCostPerWorkerHour: 0.1 }), RangeError);
});

test("zero working capital avoids division by zero and invalid inputs are rejected", () => {
  const noCost = calculateSimulator({ clientPaymentDays: 0, workerHourlyRate: 0, clientHourlyRate: 0, monthlyFixedCost: 0, hiringChannelCostPerWorkerHour: 0 });
  assert.equal(noCost.beforeFee.initialWorkingCapital, 0);
  assert.equal(noCost.beforeFee.annualGrossProfitCapitalReturn, 0);
  assert.throws(() => calculateSimulator({ workers: 0 }), RangeError);
  assert.throws(() => calculateSimulator({ clientPaymentDays: -1 }), RangeError);
  assert.throws(() => calculateSimulator({ annualFinancingRate: 1.2 }), RangeError);
});

test("monthly insurance defaults to 60 per worker and explicit operating cost overrides are preserved", () => {
  assert.equal(calculateSimulator({ workers: 500, accidentInsurancePerWorkerMonth: 80 }).input.monthlyFixedCost, 40000);
  assert.equal(DEFAULTS.monthlyFixedCost, 60000);
  for (const workers of [100, 500, 1000]) {
    const result = calculateSimulator({ workers });
    const noInsurance = calculateSimulator({ workers, monthlyFixedCost: 0 });
    assert.equal(result.input.monthlyFixedCost, workers * 60);
    for (const scenario of ["beforeFee", "afterFee"]) {
      assert.equal(result[scenario].monthlyGrossProfit, noInsurance[scenario].monthlyGrossProfit);
      assert.equal(result[scenario].annualOperatingProfit, noInsurance[scenario].annualOperatingProfit - workers * 60 * 12);
      assert.equal(result[scenario].rows[0].operatingCashFlow, noInsurance[scenario].rows[0].operatingCashFlow - workers * 60);
    }
  }
  assert.equal(calculateSimulator({ workers: 500, monthlyFixedCost: 1234 }).input.monthlyFixedCost, 1234);
});

test("monthly fixed operating expenses are itemized and deducted exactly once in both scenarios", () => {
  for (const input of [{}, { workers: 100 }, { workers: 500, monthlyFixedCost: 1234 }, { monthlyFixedCost: 0 }]) {
    const result = calculateSimulator(input);
    for (const scenario of [result.beforeFee, result.afterFee]) {
      for (const row of scenario.rows) {
        assert.equal(row.fixedOperatingCost, result.input.monthlyFixedCost);
        closeTo(row.operatingCashFlow, row.customerCollection - row.wagePayment - row.hiringChannelCost - row.fixedOperatingCost - row.platformFee);
        closeTo(row.grossProfit, row.revenue - row.directLaborCost - row.hiringChannelCost);
      }
      for (let year = 0; year < 2; year += 1) {
        const rows = scenario.rows.slice(year * 12, (year + 1) * 12);
        assert.equal(rows.reduce((total, row) => total + row.fixedOperatingCost, 0), result.input.monthlyFixedCost * 12);
        closeTo(scenario.years[year].operatingCashFlow, rows.reduce((total, row) => total + row.operatingCashFlow, 0));
      }
    }
  }
});

test("annual cost profit rate and capital return use the same profit and distinct denominators", () => {
  for (const input of [{}, { annualFinancingRate: 0.04, clientPaymentDays: 90 }, { hiringChannelCostPerWorkerHour: 2 }, { clientPaymentDays: 0, workerHourlyRate: 0, clientHourlyRate: 0, monthlyFixedCost: 0, hiringChannelCostPerWorkerHour: 0 }]) {
    const result = calculateSimulator(input);
    for (const scenario of [result.beforeFee, result.afterFee]) {
      const cost = (scenario.monthlyWages + scenario.monthlyHiringChannelCost + result.input.monthlyFixedCost + scenario.monthlyPlatformFee) * 12 + scenario.annualFinancingCost;
      assert.equal(scenario.annualOperatingCost, cost);
      closeTo(scenario.monthlyRevenue * 12 - cost, scenario.annualOperatingProfitAfterFinancing);
      assert.equal(scenario.annualCostProfitRate, cost > 0 ? scenario.annualOperatingProfitAfterFinancing / cost : null);
      assert.equal(scenario.annualNetOperatingCapitalReturn, scenario.initialWorkingCapital > 0 ? scenario.annualOperatingProfitAfterFinancing / scenario.initialWorkingCapital : null);
    }
  }
});
