package com.trevorism.config

import com.trevorism.https.AppClientSecureHttpClient
import com.trevorism.https.SecureHttpClient
import jakarta.inject.Named
import jakarta.inject.Singleton

/**
 * App-credentials SecureHttpClient (uses clientId/clientSecret from secrets.properties). Unlike the
 * default pass-through bean, this authenticates with the app's own token obtained at call time, so it
 * works from the unauthenticated inbound webhook path and never depends on a request scope.
 * Mirrors event-tester's EventTesterSecureHttpClient.
 */
@Singleton
@Named("promptTesterSecureHttpClient")
class PromptTesterSecureHttpClient extends AppClientSecureHttpClient implements SecureHttpClient {
}
