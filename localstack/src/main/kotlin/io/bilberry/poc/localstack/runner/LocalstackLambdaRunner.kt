package io.bilberry.poc.localstack.runner

import org.slf4j.LoggerFactory
import org.testcontainers.containers.localstack.LocalStackContainer
import org.testcontainers.containers.localstack.LocalStackContainer.Service
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient
import software.amazon.awssdk.services.cloudwatchlogs.model.DescribeLogStreamsRequest
import software.amazon.awssdk.services.cloudwatchlogs.model.GetLogEventsRequest
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.CreateFunctionRequest
import software.amazon.awssdk.services.lambda.model.FunctionCode
import software.amazon.awssdk.services.lambda.model.GetFunctionRequest
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.lambda.model.PackageType
import software.amazon.awssdk.services.lambda.model.State
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import kotlin.io.path.Path


object LocalstackLambdaRunner {

    private val AWS_DEFAULT_REGION = Region.US_EAST_1
    private const val AWS_BUCKET_NAME = "lambda-image"
    private const val AWS_BUCKET_KEY = "lambda.jar"
    private const val LAMBDA_SOURCE_PATH = "./libs/lambda.jar"
    private const val AWS_LAMBDA_NAME = "test-lambda-name"

    private val logger = LoggerFactory.getLogger(LocalstackLambdaRunner::class.java)

    @JvmStatic
    fun main(vararg args: String) {
        val lambdaHandler = getLambdaHandler(args)

        logger.info("Starting Localstack container...")
        val localstackDockerImage = DockerImageName.parse("localstack/localstack:latest")
        LocalStackContainer(localstackDockerImage)
            .withServices(Service.LAMBDA, Service.S3, Service.CLOUDWATCHLOGS).use { container ->
                container.start()

                logger.info("Configuring S3 service and uploading lambda package")
                val s3 = S3Client.builder()
                    .endpointOverride(container.getEndpointOverride(Service.S3))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("TEST_AWS_KEY", "TEST_AWS_SECRET")
                        )
                    )
                    .region(AWS_DEFAULT_REGION)
                    .build()

                s3.createBucket(CreateBucketRequest.builder().bucket(AWS_BUCKET_NAME).build())
                s3.putObject(
                    PutObjectRequest.builder().bucket(AWS_BUCKET_NAME).key(AWS_BUCKET_KEY) .build(),
                    Path(LAMBDA_SOURCE_PATH)
                )

                logger.info("Configuring lambda to run...")
                val lambdaClient = LambdaClient.builder()
                    .endpointOverride(container.getEndpointOverride(Service.LAMBDA))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("TEST_AWS_KEY", "TEST_AWS_SECRET")
                        )
                    )
                    .region(AWS_DEFAULT_REGION)
                    .build()

                lambdaClient.createFunction(
                    CreateFunctionRequest.builder()
                        .functionName(AWS_LAMBDA_NAME)
                        .runtime("java17")
                        .packageType(PackageType.ZIP)
                        .role("arn:aws:iam::000000000000:role/non-existing")
                        .handler(lambdaHandler)
                        .code(FunctionCode.builder().s3Bucket(AWS_BUCKET_NAME).s3Key(AWS_BUCKET_KEY).build())
                        .build()
                )

                val functionRequest = GetFunctionRequest.builder().functionName(AWS_LAMBDA_NAME).build()

                do {
                    val fState = lambdaClient.getFunction(functionRequest).configuration().state()
                    if (fState != State.ACTIVE) {
                        logger.info("Checking lambda state - expect ${State.ACTIVE.name}, but current $fState")
                        Thread.sleep(500)
                    }
                } while (fState != State.ACTIVE)

                logger.info("Invoking lambda...")
                lambdaClient.invoke(
                    InvokeRequest
                        .builder()
                        .functionName(AWS_LAMBDA_NAME)
                        .payload(SdkBytes.fromString("""
                            {"speech": "Bumbambum Baa Ba Du Du Dam"}
                        """.trimIndent(), Charsets.UTF_8))
                        .build()
                )

                logger.info("Requesting Cloudwatch logs...")
                val cloudWatchLogsClient = CloudWatchLogsClient.builder()
                    .endpointOverride(container.getEndpointOverride(Service.CLOUDWATCHLOGS))
                    .credentialsProvider(
                        StaticCredentialsProvider.create(
                            AwsBasicCredentials.create("TEST_AWS_KEY", "TEST_AWS_SECRET")
                        )
                    )
                    .region(AWS_DEFAULT_REGION)
                    .build()

                val logGroupName = lambdaClient.getFunction(functionRequest).configuration().loggingConfig().logGroup()
                val logStreamsResponse = cloudWatchLogsClient.describeLogStreams(
                    DescribeLogStreamsRequest.builder().logGroupName(logGroupName).build()
                )
                logStreamsResponse.logStreams().forEach { logStream ->
                    val logEvents = cloudWatchLogsClient.getLogEvents(
                        GetLogEventsRequest
                            .builder()
                            .logGroupName(logGroupName)
                            .logStreamName(logStream.logStreamName())
                            .build()
                    )
                    logEvents.events().forEach { logEvent ->
                        logger.info("Log entry: ${logEvent.message()}")
                    }
                }

                logger.info("Attempting to stop Localstack container...")
                container.stop()
            }
        logger.info("Done")
    }

    private fun getLambdaHandler(args: Array<out String>): String {
        if (args.size != 1) {
            error("Only one argument is allowed to run app: [--async | --sync]")
        }
        return when (args[0].uppercase()) {
            "--ASYNC" -> "io.bilberry.poc.localstack.lambda.async.AsyncLambda::handleRequest"
            "--SYNC" -> "io.bilberry.poc.localstack.lambda.sync.SyncLambda::handleRequest"
            else -> error("Unknown app mode. Allowed: : [--async | --sync]")
        }
    }
}