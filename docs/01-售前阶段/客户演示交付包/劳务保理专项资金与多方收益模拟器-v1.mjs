const DAYS_PER_YEAR = 365;
const MONTHS_PER_YEAR = 12;

export const DEFAULTS = Object.freeze({
  monthlyReceivablePrincipal: 10_000_000,
  projectMonths: 12,
  advanceRatio: 0.8,
  tenorDays: 90,
  externalAnnualRate: 0.05,
  externalMonthlyFixedFee: 0,
  fundingSourceType: "employer-company",
  funderReturnRate: 0.025,
  funderCapitalCostRate: 0.02,
  liquidityReserveRate: 0.002,
  platformServiceRate: 0.003,
  platformMonthlyFixedFee: 0,
  platformOperatingCostRate: 0.001,
  factoringServiceRate: 0.003,
  factoringMonthlyFixedFee: 0,
  factoringOperatingCostRate: 0.001,
  monthlyReceivableRiskRate: 0.0002,
  monthlyFundingWithdrawalRiskRate: 0.0001,
  monthlyCorrelationRiskRate: 0.0001,
  monthlyOtherRiskRate: 0.0001,
  riskShareSupplier: 0.5,
  riskShareEmployer: 0.1,
  riskShareFunder: 0.2,
  riskShareFactoring: 0.2,
  riskSharePlatform: 0,
  supplierCommercialCostShare: 1,
  monthlySupplierProfitBeforeFinancing: 200_000,
  monthlySupplierProfitWithoutFinancing: 0,
  monthlyEmployerOperationalValue: 60_000,
});

const RATE_KEYS = [
  "advanceRatio",
  "externalAnnualRate",
  "funderReturnRate",
  "funderCapitalCostRate",
  "liquidityReserveRate",
  "platformServiceRate",
  "platformOperatingCostRate",
  "factoringServiceRate",
  "factoringOperatingCostRate",
  "monthlyReceivableRiskRate",
  "monthlyFundingWithdrawalRiskRate",
  "monthlyCorrelationRiskRate",
  "monthlyOtherRiskRate",
  "riskShareSupplier",
  "riskShareEmployer",
  "riskShareFunder",
  "riskShareFactoring",
  "riskSharePlatform",
  "supplierCommercialCostShare",
];

function finite(value, name) {
  if (value === undefined || value === null || String(value).trim() === "") throw new RangeError(`${name}不能为空`);
  const number = Number(value);
  if (!Number.isFinite(number)) throw new RangeError(`${name}必须是有限数字`);
  return number;
}

function nonNegative(value, name) {
  const number = finite(value, name);
  if (number < 0) throw new RangeError(`${name}不能为负数`);
  return number;
}

function rate(value, name) {
  const number = finite(value, name);
  if (number < 0 || number > 1) throw new RangeError(`${name}须为0至100%的小数`);
  return number;
}

export function normalizeInputs(rawInput = {}) {
  const values = { ...DEFAULTS, ...rawInput };
  const result = {};
  result.monthlyReceivablePrincipal = nonNegative(values.monthlyReceivablePrincipal, "月度合格应收账款");
  if (result.monthlyReceivablePrincipal <= 0) throw new RangeError("月度合格应收账款必须大于0");
  result.projectMonths = finite(values.projectMonths, "项目履约月数");
  if (!Number.isInteger(result.projectMonths) || result.projectMonths < 1 || result.projectMonths > MONTHS_PER_YEAR) throw new RangeError("项目履约月数须为1至12个月的整数");
  result.tenorDays = finite(values.tenorDays, "融资到回款天数");
  if (!Number.isInteger(result.tenorDays) || result.tenorDays < 1 || result.tenorDays > 3650) throw new RangeError("融资到回款天数须为1至3650天的整数");
  for (const key of RATE_KEYS) result[key] = rate(values[key], key);
  for (const key of ["externalMonthlyFixedFee", "platformMonthlyFixedFee", "factoringMonthlyFixedFee"]) result[key] = nonNegative(values[key], key);
  result.monthlySupplierProfitBeforeFinancing = finite(values.monthlySupplierProfitBeforeFinancing, "供应商月度融资前项目利润");
  result.monthlySupplierProfitWithoutFinancing = finite(values.monthlySupplierProfitWithoutFinancing, "供应商月度无融资时项目利润");
  result.monthlyEmployerOperationalValue = finite(values.monthlyEmployerOperationalValue, "用工方月度经营价值");
  if (!["employer-company", "founder-individual"].includes(values.fundingSourceType)) throw new RangeError("资金来源身份无效");
  result.fundingSourceType = values.fundingSourceType;
  const riskShareTotal = [
    result.riskShareSupplier,
    result.riskShareEmployer,
    result.riskShareFunder,
    result.riskShareFactoring,
    result.riskSharePlatform,
  ].reduce((sum, value) => sum + value, 0);
  if (Math.abs(riskShareTotal - 1) > 0.000001) throw new RangeError("风险承担比例合计必须等于100%");
  return result;
}

function amountOnTerm(principal, annualRate, days) {
  return principal * annualRate * days / DAYS_PER_YEAR;
}

function annualize(monthly, months) {
  return monthly * months;
}

export function calculateSimulator(rawInput = {}) {
  const input = normalizeInputs(rawInput);
  const monthlyFinancedPrincipal = input.monthlyReceivablePrincipal * input.advanceRatio;
  const annualFinancedPrincipal = annualize(monthlyFinancedPrincipal, input.projectMonths);
  const averageOutstandingPrincipal = monthlyFinancedPrincipal * input.tenorDays / 30;
  const monthlyExternalCost = amountOnTerm(monthlyFinancedPrincipal, input.externalAnnualRate, input.tenorDays) + input.externalMonthlyFixedFee;
  const monthlyFundingReturn = amountOnTerm(monthlyFinancedPrincipal, input.funderReturnRate, input.tenorDays);
  const monthlyPlatformServiceFee = amountOnTerm(monthlyFinancedPrincipal, input.platformServiceRate, input.tenorDays) + input.platformMonthlyFixedFee;
  const monthlyFactoringServiceFee = amountOnTerm(monthlyFinancedPrincipal, input.factoringServiceRate, input.tenorDays) + input.factoringMonthlyFixedFee;
  const monthlyPlatformOperatingCost = amountOnTerm(monthlyFinancedPrincipal, input.platformOperatingCostRate, input.tenorDays);
  const monthlyFactoringOperatingCost = amountOnTerm(monthlyFinancedPrincipal, input.factoringOperatingCostRate, input.tenorDays);
  const monthlyCommercialCharges = monthlyFundingReturn + monthlyPlatformServiceFee + monthlyFactoringServiceFee + monthlyPlatformOperatingCost + monthlyFactoringOperatingCost;
  const riskRates = {
    receivable: input.monthlyReceivableRiskRate,
    fundingWithdrawal: input.monthlyFundingWithdrawalRiskRate,
    correlation: input.monthlyCorrelationRiskRate,
    other: input.monthlyOtherRiskRate,
  };
  const totalMonthlyRiskRate = Object.values(riskRates).reduce((sum, value) => sum + value, 0);
  const monthlyExpectedRiskLoss = monthlyFinancedPrincipal * totalMonthlyRiskRate;
  const monthlySpecialTotalCost = monthlyCommercialCharges + monthlyExpectedRiskLoss;
  const monthlyQuotedCostAdvantage = monthlyExternalCost - monthlySpecialTotalCost;
  const monthlySupplierCommercialBurden = monthlyCommercialCharges * input.supplierCommercialCostShare;
  const monthlyEmployerCommercialSubsidy = monthlyCommercialCharges - monthlySupplierCommercialBurden;
  const monthlyRiskLoss = {
    supplier: monthlyExpectedRiskLoss * input.riskShareSupplier,
    employer: monthlyExpectedRiskLoss * input.riskShareEmployer,
    funder: monthlyExpectedRiskLoss * input.riskShareFunder,
    factoring: monthlyExpectedRiskLoss * input.riskShareFactoring,
    platform: monthlyExpectedRiskLoss * input.riskSharePlatform,
  };
  const monthlySupplierFinancingBurden = monthlySupplierCommercialBurden + monthlyRiskLoss.supplier;
  const monthlySupplierFinalProfit = input.monthlySupplierProfitBeforeFinancing - monthlySupplierFinancingBurden;
  const monthlySupplierIncrementalBenefit = monthlySupplierFinalProfit - input.monthlySupplierProfitWithoutFinancing;
  const monthlyFunderOpportunityCost = amountOnTerm(monthlyFinancedPrincipal, input.funderCapitalCostRate, input.tenorDays);
  const monthlyLiquidityReserveCost = amountOnTerm(monthlyFinancedPrincipal, input.liquidityReserveRate, input.tenorDays);
  const monthlyFundingSourceNetBenefit = monthlyFundingReturn - monthlyFunderOpportunityCost - monthlyLiquidityReserveCost - monthlyRiskLoss.funder;
  const monthlyEmployerOperatingNetBenefit = input.monthlyEmployerOperationalValue - monthlyEmployerCommercialSubsidy - monthlyRiskLoss.employer;
  const monthlyEmployerNetBenefit = input.fundingSourceType === "employer-company"
    ? monthlyEmployerOperatingNetBenefit + monthlyFundingSourceNetBenefit
    : monthlyEmployerOperatingNetBenefit;
  const monthlyFactoringNetBenefit = monthlyFactoringServiceFee - monthlyFactoringOperatingCost - monthlyRiskLoss.factoring;
  const monthlyPlatformNetBenefit = monthlyPlatformServiceFee - monthlyPlatformOperatingCost - monthlyRiskLoss.platform;
  const monthlySystemEconomicCost = monthlyFunderOpportunityCost + monthlyLiquidityReserveCost + monthlyPlatformOperatingCost + monthlyFactoringOperatingCost + monthlyExpectedRiskLoss;
  const monthlyEconomicValueAdvantage = monthlyExternalCost - monthlySystemEconomicCost;
  const partiesPositive = {
    supplier: monthlySupplierFinalProfit > 0,
    employer: monthlyEmployerNetBenefit > 0,
    fundingSource: monthlyFundingSourceNetBenefit > 0,
    factoring: monthlyFactoringNetBenefit > 0,
    platform: monthlyPlatformNetBenefit > 0,
  };
  const economicFeasible = monthlyQuotedCostAdvantage > 0
    && monthlySupplierIncrementalBenefit > 0
    && Object.values(partiesPositive).every(Boolean);
  const annual = {
    financedPrincipal: annualFinancedPrincipal,
    externalCost: annualize(monthlyExternalCost, input.projectMonths),
    fundingReturn: annualize(monthlyFundingReturn, input.projectMonths),
    platformServiceFee: annualize(monthlyPlatformServiceFee, input.projectMonths),
    factoringServiceFee: annualize(monthlyFactoringServiceFee, input.projectMonths),
    platformOperatingCost: annualize(monthlyPlatformOperatingCost, input.projectMonths),
    factoringOperatingCost: annualize(monthlyFactoringOperatingCost, input.projectMonths),
    commercialCharges: annualize(monthlyCommercialCharges, input.projectMonths),
    expectedRiskLoss: annualize(monthlyExpectedRiskLoss, input.projectMonths),
    specialTotalCost: annualize(monthlySpecialTotalCost, input.projectMonths),
    quotedCostAdvantage: annualize(monthlyQuotedCostAdvantage, input.projectMonths),
    supplierCommercialBurden: annualize(monthlySupplierCommercialBurden, input.projectMonths),
    employerCommercialSubsidy: annualize(monthlyEmployerCommercialSubsidy, input.projectMonths),
    supplierFinancingBurden: annualize(monthlySupplierFinancingBurden, input.projectMonths),
    supplierFinalProfit: annualize(monthlySupplierFinalProfit, input.projectMonths),
    supplierIncrementalBenefit: annualize(monthlySupplierIncrementalBenefit, input.projectMonths),
    funderOpportunityCost: annualize(monthlyFunderOpportunityCost, input.projectMonths),
    liquidityReserveCost: annualize(monthlyLiquidityReserveCost, input.projectMonths),
    fundingSourceNetBenefit: annualize(monthlyFundingSourceNetBenefit, input.projectMonths),
    employerOperatingNetBenefit: annualize(monthlyEmployerOperatingNetBenefit, input.projectMonths),
    employerNetBenefit: annualize(monthlyEmployerNetBenefit, input.projectMonths),
    factoringNetBenefit: annualize(monthlyFactoringNetBenefit, input.projectMonths),
    platformNetBenefit: annualize(monthlyPlatformNetBenefit, input.projectMonths),
    systemEconomicCost: annualize(monthlySystemEconomicCost, input.projectMonths),
    economicValueAdvantage: annualize(monthlyEconomicValueAdvantage, input.projectMonths),
    riskLoss: Object.fromEntries(Object.entries(monthlyRiskLoss).map(([key, value]) => [key, annualize(value, input.projectMonths)])),
  };
  const monthly = {
    financedPrincipal: monthlyFinancedPrincipal,
    externalCost: monthlyExternalCost,
    fundingReturn: monthlyFundingReturn,
    platformServiceFee: monthlyPlatformServiceFee,
    factoringServiceFee: monthlyFactoringServiceFee,
    platformOperatingCost: monthlyPlatformOperatingCost,
    factoringOperatingCost: monthlyFactoringOperatingCost,
    commercialCharges: monthlyCommercialCharges,
    expectedRiskLoss: monthlyExpectedRiskLoss,
    specialTotalCost: monthlySpecialTotalCost,
    quotedCostAdvantage: monthlyQuotedCostAdvantage,
    supplierCommercialBurden: monthlySupplierCommercialBurden,
    employerCommercialSubsidy: monthlyEmployerCommercialSubsidy,
    supplierFinancingBurden: monthlySupplierFinancingBurden,
    supplierFinalProfit: monthlySupplierFinalProfit,
    supplierIncrementalBenefit: monthlySupplierIncrementalBenefit,
    funderOpportunityCost: monthlyFunderOpportunityCost,
    liquidityReserveCost: monthlyLiquidityReserveCost,
    fundingSourceNetBenefit: monthlyFundingSourceNetBenefit,
    employerOperatingNetBenefit: monthlyEmployerOperatingNetBenefit,
    employerNetBenefit: monthlyEmployerNetBenefit,
    factoringNetBenefit: monthlyFactoringNetBenefit,
    platformNetBenefit: monthlyPlatformNetBenefit,
    systemEconomicCost: monthlySystemEconomicCost,
    economicValueAdvantage: monthlyEconomicValueAdvantage,
    riskLoss: monthlyRiskLoss,
  };
  return {
    input,
    monthly,
    annual,
    monthlyFinancedPrincipal,
    annualFinancedPrincipal,
    averageOutstandingPrincipal,
    riskRates,
    totalMonthlyRiskRate,
    partiesPositive,
    economicFeasible,
    quotedCostAdvantageRate: monthlyFinancedPrincipal ? monthlyQuotedCostAdvantage / monthlyFinancedPrincipal : null,
    economicValueAdvantageRate: monthlyFinancedPrincipal ? monthlyEconomicValueAdvantage / monthlyFinancedPrincipal : null,
    sensitivity: [0.04, 0.05, 0.06].map((annualRate) => {
      const monthlyExternalCost = amountOnTerm(monthlyFinancedPrincipal, annualRate, input.tenorDays) + input.externalMonthlyFixedFee;
      return {
        annualRate,
        monthlyExternalCost,
        monthlyQuotedCostAdvantage: monthlyExternalCost - monthlySpecialTotalCost,
        annualExternalCost: annualize(monthlyExternalCost, input.projectMonths),
        annualQuotedCostAdvantage: annualize(monthlyExternalCost - monthlySpecialTotalCost, input.projectMonths),
      };
    }),
  };
}
