package com.deylauncher.servers;

import com.deylauncher.identity.AccountType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The cloud slot limits: 1 for an offline account, 5 for a Microsoft one. */
class SemiHostedLimitsTest {

    @Test
    void offlineAccountsGetOneSlotAndOnlineAccountsGetFive() {
        assertEquals(1, SemiHostedLimits.maxFor(AccountType.OFFLINE));
        assertEquals(5, SemiHostedLimits.maxFor(AccountType.ONLINE));
    }

    @Test
    void anUnknownAccountTypeIsTreatedAsTheStricterOfflineCase() {
        assertEquals(SemiHostedLimits.OFFLINE_MAX, SemiHostedLimits.maxFor(null));
        assertEquals(1, SemiHostedLimits.maxForWire(null));
        assertEquals(1, SemiHostedLimits.maxForWire("   "));
        assertEquals(1, SemiHostedLimits.maxForWire("garbage"));
    }

    @Test
    void wireValuesResolveTheRightCap() {
        assertEquals(5, SemiHostedLimits.maxForWire("ONLINE"));
        assertEquals(5, SemiHostedLimits.maxForWire("online"));
        assertEquals(1, SemiHostedLimits.maxForWire("OFFLINE"));
    }

    @Test
    void theCapIsInclusiveOfTheLastAllowedServer() {
        assertTrue(SemiHostedLimits.canAddAnother(AccountType.OFFLINE, 0));
        assertFalse(SemiHostedLimits.canAddAnother(AccountType.OFFLINE, 1));
        assertTrue(SemiHostedLimits.canAddAnother(AccountType.ONLINE, 4));
        assertFalse(SemiHostedLimits.canAddAnother(AccountType.ONLINE, 5));
    }

    @Test
    void messagesExplainTheLimitAndHowToRaiseIt() {
        assertTrue(SemiHostedLimits.describe(AccountType.OFFLINE).contains("1"));
        assertTrue(SemiHostedLimits.capReachedMessage(AccountType.OFFLINE).contains("Microsoft"));
        assertFalse(SemiHostedLimits.capReachedMessage(AccountType.ONLINE).contains("Microsoft"));
        assertEquals("2 of 5 cloud slots used", SemiHostedLimits.usage(AccountType.ONLINE, 2));
        assertEquals("1 of 1 cloud slot used", SemiHostedLimits.usage(AccountType.OFFLINE, 1));
    }
}
