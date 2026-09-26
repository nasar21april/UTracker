package com.example.upstoxmarketdataapp.simulator

import com.upstox.feeder.MarketUpdateV3
import com.upstox.marketdatafeederv3udapi.rpc.proto.MarketDataFeedV3

object MarketUpdateAdapter {
    fun getFeeds(marketUpdate: Any?): Map<String, Any> {
        if (marketUpdate == null) return emptyMap()
        if (marketUpdate is MarketUpdateV3) {
            return marketUpdate.feeds ?: emptyMap()
        }
        if (marketUpdate is MarketDataFeedV3.FeedResponse) {
            return marketUpdate.feedsMap
        }
        return emptyMap()
    }

    fun getLtp(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.ltpc?.ltp
                ?: feed.fullFeed?.indexFF?.ltpc?.ltp
                ?: feed.fullFeed?.marketFF?.ltpc?.ltp
                ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasLtpc()) return feed.ltpc.ltp
            if (feed.hasFullFeed()) {
                if (feed.fullFeed.hasIndexFF() && feed.fullFeed.indexFF.hasLtpc()) return feed.fullFeed.indexFF.ltpc.ltp
                if (feed.fullFeed.hasMarketFF() && feed.fullFeed.marketFF.hasLtpc()) return feed.fullFeed.marketFF.ltpc.ltp
            }
        }
        return 0.0
    }

    fun getClose(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.ltpc?.cp ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasLtpc()) return feed.ltpc.cp
        }
        return 0.0
    }

    fun getAtp(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.fullFeed?.marketFF?.atp ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasFullFeed() && feed.fullFeed.hasMarketFF()) return feed.fullFeed.marketFF.atp
        }
        return 0.0
    }

    fun getOi(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.fullFeed?.marketFF?.oi ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasFullFeed() && feed.fullFeed.hasMarketFF()) return feed.fullFeed.marketFF.oi
        }
        return 0.0
    }

    fun getDelta(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.fullFeed?.marketFF?.optionGreeks?.delta ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasFullFeed() && feed.fullFeed.hasMarketFF() && feed.fullFeed.marketFF.hasOptionGreeks()) {
                return feed.fullFeed.marketFF.optionGreeks.delta
            }
        }
        return 0.0
    }

    fun getTbq(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.fullFeed?.marketFF?.tbq ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasFullFeed() && feed.fullFeed.hasMarketFF()) return feed.fullFeed.marketFF.tbq
        }
        return 0.0
    }

    fun getTsq(feed: Any?): Double {
        if (feed is MarketUpdateV3.Feed) {
            return feed.fullFeed?.marketFF?.tsq ?: 0.0
        }
        if (feed is MarketDataFeedV3.Feed) {
            if (feed.hasFullFeed() && feed.fullFeed.hasMarketFF()) return feed.fullFeed.marketFF.tsq
        }
        return 0.0
    }
}
