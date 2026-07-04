package com.trevorism.config

import com.trevorism.event.ChannelClient
import com.trevorism.event.DefaultChannelClient
import com.trevorism.https.SecureHttpClient
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

/**
 * Exposes a ChannelClient for managing event topics/subscriptions on event.data.trevorism.com.
 *
 * This is a lazy @Singleton (no @Context / eager init), so it does not resolve SecureHttpClient at
 * boot -- the injected client is request-scoped and is only touched when /test runs.
 */
@Factory
class EventClientFactory {

    @Singleton
    ChannelClient channelClient(SecureHttpClient secureHttpClient) {
        new DefaultChannelClient(secureHttpClient)
    }
}
