package com.example.upstoxmarketdataapp.data

data class OptionData(
    val instrumentKey: String,
    val symbolName: String = instrumentKey.substringAfter("|"),
    val ltp: Double = 0.0,
    val atp: Double = 0.0,
    val oi: Double = 0.0,
    val yoi: Double = 0.0, // Yesterday's Open Interest
    val delta: Double = 0.0,
    val tbq: Double = 0.0, // Total Buy Quantity
    val tsq: Double = 0.0, // Total Sell Quantity
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    val diff: Double
        get() = if (ltp > 0.0 && atp > 0.0) ltp - atp else 0.0

    val oiChange: Double
        get() = oi - yoi
}

data class OptionSignal(
    val type: String, // "BUY CALL", "BUY PUT", or "NONE"
    val strikePrice: Double,
    val entryPrice: Double,
    val exitIndication: String
)

data class FutureData(
    val instrumentKey: String,
    val symbolName: String = instrumentKey.substringAfter("|"),
    val expiry: Long = 0L,
    val ltp: Double = 0.0,
    val atp: Double = 0.0,
    val lastUpdateTime: Long = System.currentTimeMillis()
) {
    val diff: Double
        get() = if (ltp > 0.0 && atp > 0.0) ltp - atp else 0.0
}

data class Instrument(
    val instrumentKey: String,
    val assetSymbol: String,
    val expiry: Long,
    val strikePrice: Double,
    val instrumentType: String, // "CE" or "PE"
    val lotSize: Int
)

data class StrikeRowState(
    val strikePrice: Double,
    val strikeLabel: String, // e.g. "Current", "1 Below", etc.
    val ceData: OptionData? = null,
    val peData: OptionData? = null,
    val ltp: Double = 0.0,
    val expiryStr: String = "", // For Option Chain UI and Watermark
    val updn: String = "--" // We keep this for compatibility and default representation
) {
    val ceOiChange: Double
        get() = (ceData?.oi ?: 0.0) - (ceData?.yoi ?: 0.0)

    val peOiChange: Double
        get() = (peData?.oi ?: 0.0) - (peData?.yoi ?: 0.0)

    val netOiChange: Double
        get() = peOiChange - ceOiChange

    val activeSignal: OptionSignal
        get() {
            val ceDiffVal = ceDiff
            val peDiffVal = peDiff
            val ceOiChg = ceOiChange
            val peOiChg = peOiChange
            val ceLtp = ceData?.ltp ?: 0.0
            val peLtp = peData?.ltp ?: 0.0

            return if (ceDiffVal > 0.0 && ceDiffVal > peDiffVal && peOiChg > ceOiChg) {
                OptionSignal(
                    type = "BUY CALL",
                    strikePrice = strikePrice,
                    entryPrice = ceLtp,
                    exitIndication = "Exit when CE Diff <= PE Diff or CE Diff <= 0"
                )
            } else if (peDiffVal > 0.0 && peDiffVal > ceDiffVal && ceOiChg > peOiChg) {
                OptionSignal(
                    type = "BUY PUT",
                    strikePrice = strikePrice,
                    entryPrice = peLtp,
                    exitIndication = "Exit when PE Diff <= CE Diff or PE Diff <= 0"
                )
            } else {
                OptionSignal(
                    type = "NONE",
                    strikePrice = strikePrice,
                    entryPrice = 0.0,
                    exitIndication = ""
                )
            }
        }
    // CE diff
    val ceDiff: Double
        get() = ceData?.diff ?: 0.0

    // PE diff
    val peDiff: Double
        get() = peData?.diff ?: 0.0

    // OPCR = pe_oi / ce_oi
    val opcr: Double?
        get() {
            val ceOi = ceData?.oi ?: 0.0
            val peOi = peData?.oi ?: 0.0
            return if (ceOi > 0.0) peOi / ceOi else null
        }

    // CPCR = (pe_oi - pe_yoi) / (ce_oi - ce_yoi)
    val cpcr: Double?
        get() {
            val ceOi = ceData?.oi ?: 0.0
            val peOi = peData?.oi ?: 0.0
            val ceYoi = ceData?.yoi ?: 0.0
            val peYoi = peData?.yoi ?: 0.0
            val ceDiffVal = ceOi - ceYoi
            return if (ceDiffVal != 0.0) (peOi - peYoi) / ceDiffVal else null
        }

    // CE Build = (CE_TBQ + PE_TSQ)
    val ceBuild: Double
        get() = (ceData?.tbq ?: 0.0) + (peData?.tsq ?: 0.0)

    // PE Build = (PE_TBQ + CE_TSQ)
    val peBuild: Double
        get() = (peData?.tbq ?: 0.0) + (ceData?.tsq ?: 0.0)

    // Up/Dn signal computed automatically
    val computedUpDn: String
        get() {
            val ceDiffVal = ceDiff
            val peDiffVal = peDiff
            val cpcrVal = cpcr
            val ceBuildVal = ceBuild
            val peBuildVal = peBuild

            return if (ceDiffVal > 0.0 && cpcrVal != null && cpcrVal > 1.0 && ceBuildVal > peBuildVal) {
                "Up"
            } else if (peDiffVal > 0.0 && cpcrVal != null && cpcrVal < 1.0 && peBuildVal > ceBuildVal) {
                "Dn"
            } else {
                "--"
            }
        }
}

data class Trade(
    val id: String = java.util.UUID.randomUUID().toString(),
    val indexSymbol: String, // NIFTY, BANKNIFTY, SENSEX
    val optionType: String, // CE or PE
    val strikePrice: Double,
    val optionToken: String, // Upstox token key
    val action: String, // BUY or SELL
    val quantity: Int,
    val entryPrice: Double,
    val currentPrice: Double,
    val timestamp: Long = System.currentTimeMillis(),
    val isClosed: Boolean = false,
    val exitPrice: Double = 0.0
) {
    val pnl: Double
        get() {
            val multiplier = if (action == "BUY") 1.0 else -1.0
            val priceToUse = if (isClosed) exitPrice else currentPrice
            return (priceToUse - entryPrice) * quantity * multiplier
        }
}
