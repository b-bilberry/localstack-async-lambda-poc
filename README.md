This POC intended to test how [Localstask](https://docs.localstack.cloud/overview/) within [Testcontainers](https://testcontainers.com/) works with lambdas which contain (or not) Kotlin coroutines.

This POC uses the following AWS services (mocked by Localstack, of course):
 - S3
 - Lambda
 - Cloudwatch Logs

The application will create a jar package in the `libs` folder, upload it to the AWS S3, create an AWS Lambda function, and invoke it. After that, the Cloudwatch Logs will be requested to ensure the lambda run.

To create a jar package the `maven-assembly-plugin` will be used.
