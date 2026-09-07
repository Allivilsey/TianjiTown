package org.allivlisey.tianjitown.core.economy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EconomyRulesTest {
    @ParameterizedTest
    @CsvSource({"19.99, 2, 1999", "-19.99, 2, -1999", "0, 2, 0",
            "123, 0, 123", "0.00000001, 8, 1", "1.2300, 2, 123",
            "92233720368547758.07, 2, 9223372036854775807"})
    void convertsExactlyRepresentableAmountsWithoutLosingPrecision(String value, int scale, long minor) {
        MoneyAmount amount = MoneyAmount.from(new BigDecimal(value), scale);
        assertEquals(minor, amount.minorUnits());
        assertEquals(scale, amount.scale());
        assertEquals(new BigDecimal(value).setScale(scale), amount.decimal());
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.001", "-0.001", "92233720368547758.08", "-92233720368547758.09"})
    void rejectsAmountsThatCannotBeStoredExactly(String value) {
        assertThrows(ArithmeticException.class, () -> MoneyAmount.from(new BigDecimal(value), 2));
    }

    @ParameterizedTest
    @CsvSource({"1999, 525, 105", "1, 4999, 0", "1, 5000, 1", "3, 5000, 2",
            "0, 9999, 0", "1999, 0, 0", "10000, 9999, 9999"})
    void taxUsesBasisPointsAndRoundsHalfUpToTheMinorUnit(long gross, int rate, long expected) {
        assertEquals(new MoneyAmount(expected, 2), new TaxRate(rate).tax(new MoneyAmount(gross, 2)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 2, 8})
    void taxPreservesTheCurrencyScale(int scale) {
        assertEquals(new MoneyAmount(105, scale), new TaxRate(525).tax(new MoneyAmount(1999, scale)));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 10000, Integer.MAX_VALUE})
    void rejectsRatesOutsideTheAllowedRange(int rate) {
        assertThrows(IllegalArgumentException.class, () -> new TaxRate(rate));
    }

    @Test
    void rejectsNegativeTaxableIncome() {
        assertThrows(IllegalArgumentException.class, () -> new TaxRate(500).tax(new MoneyAmount(-1, 2)));
    }

    @Test
    void arithmeticCannotSilentlyWrapTheBalance() {
        var maximum = new MoneyAmount(Long.MAX_VALUE, 2);
        var minimum = new MoneyAmount(Long.MIN_VALUE, 2);
        var cent = new MoneyAmount(1, 2);
        assertAll(
                () -> assertThrows(ArithmeticException.class, () -> maximum.add(cent)),
                () -> assertThrows(ArithmeticException.class, () -> minimum.subtract(cent)),
                () -> assertThrows(ArithmeticException.class, minimum::negate));
    }

    @Test
    void amountsWithDifferentScalesCannotBeCombinedOrCompared() {
        var dollars = new MoneyAmount(1, 0);
        var cents = new MoneyAmount(100, 2);
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> dollars.add(cents)),
                () -> assertThrows(IllegalArgumentException.class, () -> dollars.subtract(cents)),
                () -> assertThrows(IllegalArgumentException.class, () -> dollars.compareTo(cents)));
    }

    @Test
    void arithmeticPreservesMinorUnitsAndScale() {
        var balance = new MoneyAmount(1999, 2);
        var adjustment = new MoneyAmount(105, 2);
        assertEquals(new MoneyAmount(2104, 2), balance.add(adjustment));
        assertEquals(new MoneyAmount(1894, 2), balance.subtract(adjustment));
        assertEquals(new MoneyAmount(-1999, 2), balance.negate());
        assertTrue(balance.compareTo(adjustment) > 0);
        assertTrue(adjustment.compareTo(balance) < 0);
        assertEquals(0, balance.compareTo(new MoneyAmount(1999, 2)));
    }
}
