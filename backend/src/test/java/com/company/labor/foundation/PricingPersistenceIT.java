package com.company.labor.foundation;

import com.company.labor.common.money.CalculatedAmount;
import com.company.labor.common.money.CalculationPolicy;
import com.company.labor.common.money.UnitPrice;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 在独立 PostgreSQL 临时表上验证高精度值往返与防静默舍入约束，不创建生产计费表。 */
@SpringBootTest
class PricingPersistenceIT {
    /** 真实测试数据库访问入口。 */
    @Autowired
    private JdbcTemplate jdbc;
    /** 保证临时表与操作使用同一个事务连接的管理器。 */
    @Autowired
    private PlatformTransactionManager manager;

    /**
     * 沿用独立测试数据库和合成公钥配置，不读取开发或生产库。
     * @param registry 测试配置注册入口
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        FoundationIT.properties(registry);
    }

    /** 单价、比例、中间值和最终金额分别保留契约精度，数据库读取不经过浮点数。 */
    @Test
    void decimalValuesRoundTripExactly() {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            createFixture();
            Currency currency = Currency.getInstance("CNY");
            var price = new UnitPrice(new BigDecimal("999999999999999999999999999999.12345678"),
                    currency, "fixture.unit");
            BigDecimal ratio = new BigDecimal("0.123456789012");
            var calculated = new CalculatedAmount(new BigDecimal("0.00000000000000000000000000000001"), currency);
            var policy = new CalculationPolicy("fixture.persistence", 1, 24, RoundingMode.HALF_EVEN, RoundingMode.HALF_UP);
            var settlement = policy.settle(new CalculatedAmount(new BigDecimal("0.3625"), currency));
            jdbc.update("INSERT INTO pricing_decimal_fixture VALUES (?, ?, ?, ?)",
                    price.value(), ratio, calculated.value(), settlement.amount().amount());
            var restored = jdbc.queryForMap("SELECT * FROM pricing_decimal_fixture");
            assertEquals(0, price.value().compareTo((BigDecimal) restored.get("unit_price")));
            assertEquals(0, ratio.compareTo((BigDecimal) restored.get("ratio")));
            assertEquals(0, calculated.value().compareTo((BigDecimal) restored.get("calculated")));
            assertEquals(0, settlement.amount().amount().compareTo((BigDecimal) restored.get("settled")));
        });
    }

    /** 绕开 Java 值对象直接写超精度或超容量值也应拒绝，不能先由 NUMERIC 固定位数静默舍入。 */
    @Test
    void databaseConstraintsRejectRatherThanRoundInvalidValues() {
        List<String> invalidWrites = List.of(
                "INSERT INTO pricing_decimal_fixture VALUES (0.000000001, 1, 0, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1e30, 1, 0, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 0.0000000000001, 0, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 1.01, 0, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 1, 1e-33, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 1, 1e44, 0)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 1, 0, 0.001)",
                "INSERT INTO pricing_decimal_fixture VALUES (1, 1, 0, 1e36)");
        for (String statement : invalidWrites) {
            assertThrows(DataIntegrityViolationException.class,
                    () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                        createFixture();
                        jdbc.update(statement);
                    }));
        }
    }

    /** 使用不预先舍入的 NUMERIC 加显式 CHECK 模拟未来业务表，事务提交删除、回滚撤销创建。 */
    private void createFixture() {
        jdbc.execute("""
                CREATE TEMPORARY TABLE pricing_decimal_fixture(
                    unit_price numeric NOT NULL
                        CHECK (unit_price >= 0 AND unit_price < 1e30 AND unit_price = trunc(unit_price, 8)),
                    ratio numeric NOT NULL
                        CHECK (ratio >= 0 AND ratio <= 1 AND ratio = trunc(ratio, 12)),
                    calculated numeric NOT NULL
                        CHECK (abs(calculated) < 1e44 AND calculated = trunc(calculated, 32)),
                    settled numeric NOT NULL
                        CHECK (abs(settled) < 1e36 AND settled = trunc(settled, 2))
                ) ON COMMIT DROP
                """);
    }
}
