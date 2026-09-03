package cn.tianji.town.core.economy;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EconomyRulesTest {
    @Test
    void storesMoneyAndTaxWithoutBinaryFloatingPoint() {
        MoneyAmount gross = MoneyAmount.from(new BigDecimal("19.99"), 2);
        MoneyAmount tax = new TaxRate(525).tax(gross);

        assertEquals(1999, gross.minorUnits());
        assertEquals(new BigDecimal("1.05"), tax.decimal());
    }

    @Test
    void rejectsUnrepresentableMoney() {
        assertThrows(ArithmeticException.class,
                () -> MoneyAmount.from(new BigDecimal("1.001"), 2));
    }
}
