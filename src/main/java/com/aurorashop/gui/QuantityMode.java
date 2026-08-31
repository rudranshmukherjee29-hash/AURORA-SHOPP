package com.aurorashop.gui;

/**
 * The quantity a plain left-click buy uses. Cycled via the quantity
 * toggle button in the category menu's control bar. Shift-click and the
 * sell actions have their own fixed behaviour (see design brief section 6)
 * and are unaffected by this mode.
 */
public enum QuantityMode {
    ONE(1),
    SIXTEEN(16),
    THIRTY_TWO(32),
    SIXTY_FOUR(64),
    MAX(-1); // sentinel: computed dynamically per item/player

    private final int fixedAmount;

    QuantityMode(int fixedAmount) {
        this.fixedAmount = fixedAmount;
    }

    public boolean isMax() {
        return this == MAX;
    }

    public int fixedAmount() {
        return fixedAmount;
    }

    public QuantityMode next() {
        QuantityMode[] values = values();
        return values[(ordinal() + 1) % values.length];
    }

    public String label() {
        return switch (this) {
            case ONE -> "1";
            case SIXTEEN -> "16";
            case THIRTY_TWO -> "32";
            case SIXTY_FOUR -> "64";
            case MAX -> "Max";
        };
    }
}
