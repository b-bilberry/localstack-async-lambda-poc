package io.bilberry.poc.localstack.lambda.async

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class AsyncLambda: RequestHandler<Map<String, String>, String> {
    override fun handleRequest(input: Map<String, String>, context: Context): String = runBlocking {
        println("Hello World by ${this::class.java.simpleName}")
        val speech = input["speech"] ?: "Nothing"
        println("You told me: $speech")
        suspendFun()
        "DONE"
    }

    private suspend fun suspendFun() = coroutineScope {
        launch {
            delay(500)
            println("Hello from async world!")
        }
    }
}