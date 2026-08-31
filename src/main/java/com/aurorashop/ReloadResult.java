package com.aurorashop;

import java.util.List;

/** Returned by {@code AuroraShopPlugin.reloadAll()} so /shopadmin reload can report a useful summary. */
public record ReloadResult(boolean success, int itemCount, int categoryCount, List<String> pricingWarnings) {
}
