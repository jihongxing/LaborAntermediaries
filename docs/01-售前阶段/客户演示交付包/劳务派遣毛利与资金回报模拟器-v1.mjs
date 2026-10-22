export const ACCIDENT_INSURANCE_PER_WORKER_MONTH = 60;

export const DEFAULTS = Object.freeze({
  workers: 1000,
  clientHourlyRate: 24,
  workerHourlyRate: 22,
  hoursPerDay: 12,
  workDaysPerMonth: 26,
  clientPaymentDays: 60,
  wagePaymentDays: 30,
  hiringChannelCostPerWorkerHour: 1.5,
  accidentInsurancePerWorkerMonth: ACCIDENT_INSURANCE_PER_WORKER_MONTH,
  monthlyFixedCost: 1000 * ACCIDENT_INSURANCE_PER_WORKER_MONTH,
  annualFinancingRate: 0,
  platformFeePerPersonDay: 1,
});

const MONTHS_PER_YEAR = 12;

function nonNegative(value, name) {
  const number = Number(value);
  if (!Number.isFinite(number) || number < 0) throw new RangeError(`${name}必须是非负有限数`);
  return number;
}

export function normalizeInputs(input = {}) {
  const values = { ...DEFAULTS, ...input };
  if (input.monthlyFixedCost === undefined) values.monthlyFixedCost = values.workers * values.accidentInsurancePerWorkerMonth;
  const result = {};
  for (const [key, value] of Object.entries(values)) result[key] = nonNegative(value, key);
  if (result.workers <= 0) throw new RangeError("工人数必须大于0");
  if (result.clientPaymentDays > 3650) throw new RangeError("客户回款账期不能超过3650天");
  if (![30, 60, 90, 120, 150, 180].includes(result.wagePaymentDays)) throw new RangeError("工资支付周期必须选择30天的倍数档位");
  const hourlySpread = Math.round((result.clientHourlyRate - result.workerHourlyRate) * 100) / 100;
  if (result.hiringChannelCostPerWorkerHour > hourlySpread + 0.000001) throw new RangeError("招工/渠道成本不能高于客户工价与工人工价的差额");
  if (result.hiringChannelCostPerWorkerHour > 0 && Math.abs(result.hiringChannelCostPerWorkerHour * 10 - Math.round(result.hiringChannelCostPerWorkerHour * 10)) > 0.000001) throw new RangeError("招工/渠道成本必须按0.1元递增");
  if (result.annualFinancingRate > 1) throw new RangeError("融资年利率请使用0到1之间的小数");
  return result;
}

function simulateScenario(input, includePlatformFee) {
  const monthlyHours = input.workDaysPerMonth * input.hoursPerDay;
  const monthlyRevenue = input.workers * monthlyHours * input.clientHourlyRate;
  const monthlyWages = input.workers * monthlyHours * input.workerHourlyRate;
  const monthlyHiringChannelCost = input.workers * monthlyHours * input.hiringChannelCostPerWorkerHour;
  const monthlyGrossProfit = monthlyRevenue - monthlyWages - monthlyHiringChannelCost;
  const monthlyPlatformFee = includePlatformFee
    ? input.workers * input.workDaysPerMonth * input.platformFeePerPersonDay
    : 0;
  const delayMonths = Math.ceil(input.clientPaymentDays / 30);
  const wageDelayMonths = Math.ceil(input.wagePaymentDays / 30) - 1;
  const rows = [];
  let cumulativeCashFlow = 0;
  let receivables = 0;
  let wagesPayable = 0;

  for (let month = 1; month <= 24; month += 1) {
    const customerCollection = month - delayMonths >= 1 ? monthlyRevenue : 0;
    wagesPayable += monthlyWages;
    const wagePayment = month - wageDelayMonths >= 1 ? monthlyWages : 0;
    wagesPayable -= wagePayment;
    const otherCashOutflow = monthlyHiringChannelCost + input.monthlyFixedCost + monthlyPlatformFee;
    const operatingCashFlow = customerCollection - wagePayment - otherCashOutflow;
    cumulativeCashFlow += operatingCashFlow;
    receivables += monthlyRevenue - customerCollection;
    rows.push({
      month,
      workers: input.workers,
      hours: monthlyHours * input.workers,
      revenue: monthlyRevenue,
      directLaborCost: monthlyWages,
      hiringChannelCost: monthlyHiringChannelCost,
      fixedOperatingCost: input.monthlyFixedCost,
      grossProfit: monthlyGrossProfit,
      platformFee: monthlyPlatformFee,
      customerCollection,
      wagePayment,
      otherCashOutflow,
      operatingCashFlow,
      cumulativeCashFlow,
      receivables,
      wagesPayable,
      initialCapitalCashBalance: 0,
    });
  }

  const initialWorkingCapital = Math.max(0, -Math.min(0, ...rows.map((row) => row.cumulativeCashFlow)));
  for (const row of rows) row.initialCapitalCashBalance = initialWorkingCapital + row.cumulativeCashFlow;

  const annualFinancingCost = initialWorkingCapital * input.annualFinancingRate;
  const annualGrossProfit = monthlyGrossProfit * MONTHS_PER_YEAR;
  const annualProfitAfterPlatformFee = (monthlyGrossProfit - monthlyPlatformFee) * MONTHS_PER_YEAR;
  const annualOperatingProfit = annualProfitAfterPlatformFee - input.monthlyFixedCost * MONTHS_PER_YEAR;
  const annualOperatingProfitAfterFinancing = annualOperatingProfit - annualFinancingCost;
  const annualOperatingCapitalReturn = initialWorkingCapital ? annualOperatingProfit / initialWorkingCapital : null;
  const annualNetOperatingCapitalReturn = initialWorkingCapital ? annualOperatingProfitAfterFinancing / initialWorkingCapital : null;
  let cumulativeWageCashFlow = 0;
  let minimumWageCashFlow = 0;
  for (const row of rows) {
    cumulativeWageCashFlow += row.customerCollection - row.wagePayment;
    minimumWageCashFlow = Math.min(minimumWageCashFlow, cumulativeWageCashFlow);
  }
  const yearSummary = (year) => {
    const yearRows = rows.slice((year - 1) * MONTHS_PER_YEAR, year * MONTHS_PER_YEAR);
    const sum = (key) => yearRows.reduce((total, row) => total + row[key], 0);
    const last = yearRows.at(-1);
    return {
      revenue: sum("revenue"),
      grossProfit: sum("grossProfit"),
      customerCollections: sum("customerCollection"),
      wagePayments: sum("wagePayment"),
      operatingCashFlow: sum("operatingCashFlow"),
      receivables: last.receivables,
      wagesPayable: last.wagesPayable,
      endingCashBalance: last.initialCapitalCashBalance,
    };
  };

  return {
    monthlyHours,
    monthlyRevenue,
    monthlyWages,
    monthlyHiringChannelCost,
    monthlyGrossProfit,
    monthlyPlatformFee,
    grossMargin: monthlyRevenue ? monthlyGrossProfit / monthlyRevenue : 0,
    annualGrossProfit,
    annualProfitAfterPlatformFee,
    annualOperatingProfit,
    initialWorkingCapital,
    annualFinancingCost,
    annualOperatingProfitAfterFinancing,
    annualOperatingCapitalReturn,
    annualNetOperatingCapitalReturn,
    annualOperatingReturnSpread: annualOperatingCapitalReturn === null ? null : annualOperatingCapitalReturn - input.annualFinancingRate,
    wageWorkingCapital: -minimumWageCashFlow,
    annualGrossProfitCapitalReturn: initialWorkingCapital ? annualGrossProfit / initialWorkingCapital : 0,
    annualAfterFeeCapitalReturn: initialWorkingCapital ? annualProfitAfterPlatformFee / initialWorkingCapital : 0,
    annualGrossReturnSpread: (initialWorkingCapital ? annualGrossProfit / initialWorkingCapital : 0) - input.annualFinancingRate,
    annualAfterFeeReturnSpread: (initialWorkingCapital ? annualProfitAfterPlatformFee / initialWorkingCapital : 0) - input.annualFinancingRate,
    annualGrossReturnAfterFinancing: (initialWorkingCapital ? annualGrossProfit / initialWorkingCapital : 0) - input.annualFinancingRate,
    annualAfterFeeReturnAfterFinancing: (initialWorkingCapital ? annualProfitAfterPlatformFee / initialWorkingCapital : 0) - input.annualFinancingRate,
    rows,
    years: [yearSummary(1), yearSummary(2)],
  };
}

export function calculateSimulator(rawInput = {}) {
  const input = normalizeInputs(rawInput);
  const beforeFee = simulateScenario(input, false);
  const afterFee = simulateScenario(input, true);
  for (const scenario of [beforeFee, afterFee]) {
    scenario.annualOperatingCost = (scenario.monthlyWages + scenario.monthlyHiringChannelCost + input.monthlyFixedCost + scenario.monthlyPlatformFee) * MONTHS_PER_YEAR + scenario.annualFinancingCost;
    scenario.annualCostProfitRate = scenario.annualOperatingCost > 0 ? scenario.annualOperatingProfitAfterFinancing / scenario.annualOperatingCost : null;
  }
  const stableYearlyRevenue = beforeFee.monthlyRevenue * MONTHS_PER_YEAR;
  return {
    input,
    beforeFee,
    afterFee,
    stableYearlyRevenue,
    annualFee: afterFee.monthlyPlatformFee * MONTHS_PER_YEAR,
    feePerWorkerDay: input.platformFeePerPersonDay,
    feeShareOfGrossProfit: beforeFee.monthlyGrossProfit
      ? afterFee.monthlyPlatformFee / beforeFee.monthlyGrossProfit
      : 0,
  };
}
