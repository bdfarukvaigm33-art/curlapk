
private fun executeRequest(
    url: String,
    method: String,
    headersText: String,
    bodyText: String,
    proxyHost: String,
    proxyPort: String,
    proxyUsername: String,
    proxyPassword: String,
    timeoutText: String
): RequestResult {

    require(url.isNotBlank()) {
        "Please enter a request URL."
    }

    require(url.trim().startsWith("https://", ignoreCase = true)) {
        "Only HTTPS URLs are supported."
    }

    require(method.matches(Regex("[A-Z][A-Z0-9_-]*"))) {
        "Invalid HTTP method."
    }

    val timeoutSeconds = timeoutText.toIntOrNull()
        ?: throw IllegalArgumentException("Invalid timeout.")

    require(timeoutSeconds in 5..120) {
        "Timeout must be between 5 and 120 seconds."
    }

    val parsedHeaders = headersText
        .lines()
        .filter { it.isNotBlank() }
        .map { line ->
            val separator = line.indexOf(':')

            require(separator > 0) {
                "Invalid header: $line. Use Name: Value."
            }

            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()

            require(name.isNotBlank()) {
                "Header name cannot be empty."
            }

            name to value
        }

    val contentType = parsedHeaders
        .lastOrNull { it.first.equals("Content-Type", ignoreCase = true) }
        ?.second
        ?.toMediaTypeOrNull()
        ?: "application/json; charset=utf-8".toMediaType()

    val requestBody = when {
        method == "GET" || method == "HEAD" -> {
            require(bodyText.isBlank()) {
                "$method requests cannot contain a request body."
            }
            null
        }

        method == "POST" || method == "PUT" || method == "PATCH" -> {
            bodyText.toRequestBody(contentType)
        }

        bodyText.isNotEmpty() -> {
            bodyText.toRequestBody(contentType)
        }

        else -> null
    }

    val requestBuilder = Request.Builder()
        .url(url.trim())
        .method(method, requestBody)

    parsedHeaders.forEach { (name, value) ->
        requestBuilder.addHeader(name, value)
    }

    val clientBuilder = OkHttpClient.Builder()
        .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)

    if (proxyHost.isNotBlank()) {
        val port = proxyPort.toIntOrNull()
            ?: throw IllegalArgumentException("Invalid proxy port.")

        require(port in 1..65535) {
            "Proxy port must be between 1 and 65535."
        }

        clientBuilder.proxy(
            Proxy(
                Proxy.Type.HTTP,
                InetSocketAddress(proxyHost.trim(), port)
            )
        )

        if (proxyUsername.isNotBlank()) {
            clientBuilder.proxyAuthenticator(
                object : Authenticator {
                    override fun authenticate(
                        route: Route?,
                        response: Response
                    ): Request? {
                        if (
                            response.request.header("Proxy-Authorization") != null
                        ) {
                            return null
                        }

                        return response.request.newBuilder()
                            .header(
                                "Proxy-Authorization",
                                Credentials.basic(
                                    proxyUsername,
                                    proxyPassword
                                )
                            )
                            .build()
                    }
                }
            )
        }
    }

    val client = clientBuilder.build()
    val request = requestBuilder.build()

    val startTime = System.nanoTime()

    val result = client.newCall(request).execute().use { response ->

        val duration =
            (System.nanoTime() - startTime) / 1_000_000

        val responseHeaderText =
            response.headers.toMultimap().entries.joinToString("\n") {
                "${it.key}: ${it.value.joinToString(", ")}"
            }

        val responseText =
            response.body?.string().orEmpty().take(500_000)

        RequestResult(
            code = response.code,
            message = response.message,
            duration = duration,
            headers = responseHeaderText,
            body = responseText
        )
    }

    return result
}
