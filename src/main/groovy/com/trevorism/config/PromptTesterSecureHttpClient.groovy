package com.trevorism.config

import com.trevorism.https.AppClientSecureHttpClient
import com.trevorism.https.SecureHttpClient
import jakarta.inject.Named
import jakarta.inject.Singleton

@Singleton
@Named("promptTesterSecureHttpClient")
class PromptTesterSecureHttpClient extends AppClientSecureHttpClient implements SecureHttpClient {
}
