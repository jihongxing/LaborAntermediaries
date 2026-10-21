import assert from "node:assert/strict";
import { readFileSync, existsSync } from "node:fs";
import test from "node:test";
import vm from "node:vm";

const deliveryPackageUrl = new URL("../../01-售前阶段/客户演示交付包/", import.meta.url);
const pageUrl = new URL("用工方收益与合作价值模拟器-v1.html", deliveryPackageUrl);
const html = readFileSync(pageUrl, "utf8");
const defaults = { workers: 1000, effectiveDays: 26, fee: 2, months: 12 };

function loadPage() {
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], {
    value: "", textContent: "", innerHTML: "", hidden: false, options: [], handlers: {},
    add(option) { this.options.push(option); },
    addEventListener(type, handler) { this.handlers[type] = handler; },
    removeAttribute() {},
  }]));
  let printCount = 0;
  const context = vm.createContext({
    Intl,
    Option: function (text, value) { this.text = text; this.value = value; },
    document: { getElementById(id) { assert.ok(elements.has(id), id); return elements.get(id); } },
    window: { print() { printCount += 1; } },
  });
  const scripts = [...html.matchAll(/<script[^>]*>([\s\S]*?)<\/script>/g)];
  assert.equal(scripts.length, 1);
  vm.runInContext(scripts[0][1], context);
  return {
    elements,
    calculate(input = defaults) {
      context.testInput = input;
      return vm.runInContext("calculateBuyer(testInput)", context);
    },
    printCount: () => printCount,
  };
}

test("默认项目计算实际人天和月度、年度分账", () => {
  const { calculate, elements } = loadPage();
  const result = calculate();
  assert.equal(result.monthlyPersonDays, 26000);
  assert.equal(result.annualPersonDays, 312000);
  assert.equal(result.buyerPerDayCents, 90);
  assert.equal(result.monthlyFeeCents, 5200000);
  assert.equal(result.monthlyBuyerCents, 2340000);
  assert.equal(result.monthlyPlatformCents, 2860000);
  assert.equal(result.annualBuyerCents, 28080000);
  assert.equal(result.annualFeeCents, 62400000);
  assert.equal(result.annualPlatformCents, 34320000);
  assert.equal(elements.get("monthlyBenefit").textContent, "¥23,400");
  assert.equal(elements.get("annualBenefit").textContent, "¥280,800");
  assert.match(elements.get("ruleText").textContent, /用工方分享60%，平台留存其余40%/);
  assert.match(elements.get("formulaText").textContent, /×60%＝¥0.9/);
});

test("全部十档符合基准归平台、超出部分平台四成用工方六成的规则且金额守恒", () => {
  const { calculate, elements } = loadPage();
  assert.equal(elements.get("fee").options.length, 10);
  assert.equal(elements.get("workers").options.length, 10);
  for (let index = 1; index <= 10; index += 1) {
    const result = calculate({ ...defaults, fee: index / 2 });
    assert.equal(result.buyerPerDayCents, (index - 1) * 30);
    assert.equal(result.monthlyBuyerCents + result.monthlyPlatformCents, result.monthlyFeeCents);
    assert.equal(result.annualBuyerCents + result.annualPlatformCents, result.annualFeeCents);
    assert.equal(result.tiers.length, 10);
    assert.equal(result.tiers[index - 1].monthlyBuyerCents, result.monthlyBuyerCents);
  }
});

test("对客模拟器不展示非默认分成方案或内部商业规则文档入口", () => {
  const supplier = readFileSync(new URL("劳务派遣毛利与资金回报模拟器-v1.html", deliveryPackageUrl), "utf8");
  const { elements } = loadPage();
  for (const source of [html, supplier, elements.get("ruleText").textContent]) {
    assert.doesNotMatch(source, /30%|70%|20%|80%|其他批准档位|其他已批准档|非默认|href="商业模式与收费标准/);
  }
  assert.match(elements.get("ruleText").textContent, /用工方分享60%/);
});

test("人数、半日与履约月份独立驱动所有金额", () => {
  const { calculate } = loadPage();
  const result = calculate({ workers: 100, effectiveDays: 25.5, fee: 1, months: 6 });
  assert.equal(result.monthlyPersonDays, 2550);
  assert.equal(result.monthlyBuyerCents, 76500);
  assert.equal(result.annualBuyerCents, 459000);
  for (const tier of result.tiers) {
    assert.ok(Number.isSafeInteger(tier.monthlyBuyerCents));
    assert.equal(tier.annualBuyerCents, tier.monthlyBuyerCents * 6);
  }
});

test("无履约或基准费率不虚构用工方收益", () => {
  const { calculate } = loadPage();
  assert.equal(calculate({ ...defaults, effectiveDays: 0 }).annualFeeCents, 0);
  assert.equal(calculate({ ...defaults, fee: 0.5 }).annualBuyerCents, 0);
});

test("拒绝缺失、非数字、越界和非法档位", () => {
  const { calculate } = loadPage();
  for (const [key, invalidValues] of Object.entries({
    workers: [0, 150, 1100, null, "", undefined, Infinity],
    effectiveDays: [-1, 31.5, 25.1, "abc", NaN],
    fee: [0, 0.75, 5.5],
    months: [0, 13, 1.5],
  })) {
    for (const value of invalidValues) assert.throws(() => calculate({ ...defaults, [key]: value }));
  }
  assert.throws(() => calculate({}));
});

test("每个输入均触发实时重算并更新档位表", () => {
  const { elements, calculate } = loadPage();
  const input = { ...defaults };
  for (const [key, value] of Object.entries({ workers: 500, effectiveDays: 20, fee: 5, months: 6 })) {
    input[key] = value;
    elements.get(key).value = String(value);
    elements.get(key).handlers.input();
    elements.get(key).handlers.change();
    const expected = calculate(input).annualBuyerCents / 100;
    assert.equal(elements.get("annualBenefit").textContent, `¥${new Intl.NumberFormat("zh-CN", { maximumFractionDigits: 2 }).format(expected)}`);
    assert.equal((elements.get("tierRows").innerHTML.match(/aria-current="true"/g) || []).length, 1);
  }
});

test("无效输入清除旧结果，重置恢复默认，打印行为可用", () => {
  const page = loadPage();
  const { elements } = page;
  elements.get("effectiveDays").value = "";
  elements.get("effectiveDays").handlers.input();
  assert.equal(elements.get("results").hidden, true);
  assert.equal(elements.get("businessRows").innerHTML, "");
  assert.equal(elements.get("tierRows").innerHTML, "");
  assert.match(elements.get("errorMessage").textContent, /完整填写/);
  elements.get("resetButton").handlers.click();
  assert.equal(elements.get("results").hidden, false);
  assert.equal(elements.get("annualBenefit").textContent, "¥280,800");
  assert.equal(elements.get("errorMessage").textContent, "");
  assert.match(elements.get("feedback").textContent, /已恢复/);
  elements.get("printButton").handlers.click();
  assert.equal(page.printCount(), 1);
});

test("可编辑控件仅位于项目条件，页面离线无依赖且关联文件存在", () => {
  const controls = html.match(/<(?:input|select)\b/g) || [];
  const inputSection = html.match(/<section[^>]*aria-labelledby="inputsTitle"[\s\S]*?<\/section>/)?.[0];
  assert.ok(inputSection);
  assert.equal(controls.length, 4);
  assert.equal((inputSection.match(/<(?:input|select)\b/g) || []).length, controls.length);
  assert.doesNotMatch(html, /<script[^>]+src=|<link[^>]+href=|\bfetch\s*\(|https?:\/\//i);
  for (const match of html.matchAll(/href="([^"]+)"/g)) assert.ok(existsSync(new URL(match[1], pageUrl)), match[1]);
});

test("客户演示页仅提供两个独立入口，劳务端可跳转用工方", () => {
  const presentation = readFileSync(new URL("用工方商业模式说明-客户演示版.html", deliveryPackageUrl), "utf8");
  const supplier = readFileSync(new URL("劳务派遣毛利与资金回报模拟器-v1.html", deliveryPackageUrl), "utf8");
  assert.match(presentation, /href="\.\/用工方收益与合作价值模拟器-v1\.html"/);
  assert.match(presentation, /href="\.\/劳务派遣毛利与资金回报模拟器-v1\.html"/);
  assert.match(supplier, /href="\.\/用工方收益与合作价值模拟器-v1\.html"/);
  assert.doesNotMatch(presentation, /<(?:input|select|iframe)\b|updateEstimate|supplierBidCalc|platformMonthlyCost/);
  const elements = new Map([...presentation.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], {
    handlers: {}, style: {}, addEventListener(type, handler) { this.handlers[type] = handler; },
  }]));
  const context = vm.createContext({
    document: {
      getElementById(id) { assert.ok(elements.has(id), id); return elements.get(id); },
      documentElement: { scrollHeight: 2000 },
      addEventListener() {},
    },
    window: { innerHeight: 800, scrollY: 0, addEventListener() {}, print() {} },
  });
  vm.runInContext(presentation.match(/<script[^>]*>([\s\S]*?)<\/script>/)[1], context);
  assert.equal(typeof elements.get("printButton").handlers.click, "function");
  assert.equal(typeof elements.get("guideButton").handlers.click, "function");
  assert.equal(elements.get("progressBar").style.width, "0%");
  for (const match of presentation.matchAll(/href="([^"]+\.html)"/g)) {
    assert.ok(existsSync(new URL(match[1], pageUrl)), match[1]);
  }
});

test("重写演示页以痛点组织叙事，锚点有效且不暴露内部方案", () => {
  const presentation = readFileSync(new URL("用工方商业模式说明-客户演示版.html", deliveryPackageUrl), "utf8");
  const ids = [...presentation.matchAll(/\bid="([^"]+)"/g)].map((match) => match[1]);
  assert.equal(new Set(ids).size, ids.length);
  for (const match of presentation.matchAll(/href="#([^"]+)"/g)) assert.ok(ids.includes(match[1]), match[1]);
  const order = ["why", "problem", "solution", "sourcing", "delivery", "performance", "supplier", "fees", "value", "estimate", "start"];
  for (let index = 1; index < order.length; index += 1) {
    assert.ok(presentation.indexOf(`id="${order[index - 1]}"`) < presentation.indexOf(`id="${order[index]}"`));
  }
  assert.doesNotMatch(presentation, /30%|70%|20%|80%|3\/7|2\/8|href="商业模式与收费标准|<iframe\b|https?:\/\//);
  assert.match(presentation, /具体功能与实施范围，以合作前确认为准/);
  assert.match(presentation, /没到岗的人不计费/);
  assert.match(presentation, /用同一个项目，分别看看用工方能分享多少/);
  assert.match(presentation, /让供应商来竞争，让交付有依据/);
  assert.match(presentation, /多家劳务公司一起比较/);
  assert.match(presentation, /中标之后也能持续跟进/);
  assert.match(presentation, /prefers-reduced-motion/);
});

test("演示导览支持进入退出、键盘切换和打印", () => {
  const presentation = readFileSync(new URL("用工方商业模式说明-客户演示版.html", deliveryPackageUrl), "utf8");
  const classes = new Set();
  const handlers = {};
  const scrolled = [];
  let prints = 0;
  const elements = new Map([...presentation.matchAll(/id="([^"]+)"/g)].map((match) => [match[1], {
    style: {}, attributes: {}, handlers: {}, textContent: "",
    addEventListener(type, handler) { this.handlers[type] = handler; },
    setAttribute(name, value) { this.attributes[name] = value; },
    scrollIntoView(options) { scrolled.push({ id: match[1], options }); },
    click() { this.handlers.click(); },
  }]));
  const scenes = ["problem", "solution", "sourcing"].map((id, index) => ({
    scrollIntoView(options) { scrolled.push({ id, options }); },
    getBoundingClientRect() { return { top: 90 + index * 800 }; },
  }));
  const context = vm.createContext({
    document: {
      getElementById(id) { return elements.get(id); },
      querySelectorAll() { return scenes; },
      documentElement: { scrollHeight: 2000 },
      body: { classList: {
        toggle(name) { if (classes.has(name)) { classes.delete(name); return false; } classes.add(name); return true; },
        contains(name) { return classes.has(name); },
      } },
      addEventListener(type, handler) { handlers[type] = handler; },
    },
    window: { innerHeight: 800, scrollY: 0, addEventListener() {}, print() { prints += 1; }, matchMedia() { return { matches: true }; } },
  });
  vm.runInContext(presentation.match(/<script[^>]*>([\s\S]*?)<\/script>/)[1], context);
  elements.get("guideButton").click();
  assert.equal(elements.get("guideButton").attributes["aria-pressed"], "true");
  assert.equal(scrolled.at(-1).id, "journey");
  let prevented = false;
  handlers.keydown({ key: "ArrowRight", target: { closest() { return false; } }, preventDefault() { prevented = true; } });
  assert.equal(scrolled.at(-1).id, "solution");
  assert.equal(scrolled.at(-1).options.behavior, "instant");
  assert.equal(prevented, true);
  const scrollCount = scrolled.length;
  handlers.keydown({ key: " ", target: { closest() { return true; } } });
  assert.equal(scrolled.length, scrollCount);
  handlers.keydown({ key: "Escape" });
  assert.equal(elements.get("guideButton").attributes["aria-pressed"], "false");
  elements.get("printButton").click();
  assert.equal(prints, 1);
});
