package software.amazon.cloudformation.moduleversion;

import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CfnRegistryException;
import software.amazon.awssdk.services.cloudformation.model.DescribeTypeRegistrationRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeTypeRegistrationResponse;
import software.amazon.awssdk.services.cloudformation.model.RegisterTypeRequest;
import software.amazon.awssdk.services.cloudformation.model.RegisterTypeResponse;
import software.amazon.cloudformation.exceptions.CfnGeneralServiceException;
import software.amazon.cloudformation.exceptions.CfnInvalidRequestException;
import software.amazon.cloudformation.exceptions.CfnNotStabilizedException;
import software.amazon.cloudformation.proxy.AmazonWebServicesClientProxy;
import software.amazon.cloudformation.proxy.Logger;
import software.amazon.cloudformation.proxy.ProgressEvent;
import software.amazon.cloudformation.proxy.ProxyClient;
import software.amazon.cloudformation.proxy.ResourceHandlerRequest;
import software.amazon.cloudformation.proxy.delay.Constant;

import java.time.Duration;
import java.util.Arrays;

public class CreateHandler extends BaseHandlerStd {

    private ReadHandler readHandler;
    private ArnPredictor arnPredictor;

    private static final Duration HANDLER_CALLBACK_DELAY_SECONDS = Duration.ofSeconds(15L);
    private static final Duration HANDLER_TIMEOUT_MINUTES = Duration.ofMinutes(30L);
    private static final Constant BACKOFF_STRATEGY = Constant.of().timeout(HANDLER_TIMEOUT_MINUTES).delay(HANDLER_CALLBACK_DELAY_SECONDS).build();

    public CreateHandler() {
        this(new ReadHandler(), new ArnPredictor());
    }

    CreateHandler(final ReadHandler readHandler, final ArnPredictor arnPredictor) {
        this.readHandler = readHandler;
        this.arnPredictor = arnPredictor;
    }

    protected ProgressEvent<ResourceModel, CallbackContext> handleRequest(
            final AmazonWebServicesClientProxy proxy,
            final ResourceHandlerRequest<ResourceModel> request,
            final CallbackContext callbackContext,
            final ProxyClient<CloudFormationClient> proxyClient,
            final Logger logger) {

        final ResourceModel model = request.getDesiredResourceState();
        validateModel(model);

        if (model.getArn() == null) {
            final String arn = arnPredictor.predictArn(proxy, request, callbackContext, proxyClient, logger);
            if (arn == null) {
                throw new CfnGeneralServiceException(String.format("ARN prediction for new module version of module %s", model.getModuleName()));
            }
            model.setArn(arn);
        }

        logger.log(String.format("Registering new version of module %s", model.getModuleName()));
        return proxy.initiate("AWS-CloudFormation-ModuleVersion::Create", proxyClient, model, callbackContext)
                .translateToServiceRequest(Translator::translateToCreateRequest)
                .backoffDelay(BACKOFF_STRATEGY)
                .makeServiceCall((registerTypeRequest, client) -> {
                    final RegisterTypeResponse registerTypeResponse = registerModule(registerTypeRequest, client);
                    callbackContext.setRegistrationToken(registerTypeResponse.registrationToken());
                    return registerTypeResponse;
                })
                .stabilize(this::stabilize)
                .progress()
                .then(progress -> readHandler.handleRequest(proxy, request, callbackContext, proxyClient, logger));
    }

    private RegisterTypeResponse registerModule(final RegisterTypeRequest request, final ProxyClient<CloudFormationClient> proxyClient) {
        RegisterTypeResponse response;
        try {
            response = proxyClient.injectCredentialsAndInvokeV2(request, proxyClient.client()::registerType);
        } catch (final CfnRegistryException exception) {
            logger.log(String.format("Failed to register module:\n%s", Arrays.toString(exception.getStackTrace())));
            throw new CfnGeneralServiceException(exception);
        }
        return response;
    }

    private Boolean stabilize(
            final RegisterTypeRequest registerTypeRequest,
            final RegisterTypeResponse registerTypeResponse,
            final ProxyClient<CloudFormationClient> proxyClient,
            final ResourceModel model,
            final CallbackContext callbackContext) {

        final String registrationToken = callbackContext.getRegistrationToken();

        final DescribeTypeRegistrationRequest dtrRequest = Translator
                .translateToDescribeTypeRegistrationRequest(registrationToken);

        DescribeTypeRegistrationResponse dtrResponse;
        try {
            dtrResponse = proxyClient.injectCredentialsAndInvokeV2(dtrRequest, proxyClient.client()::describeTypeRegistration);
        } catch (final CfnRegistryException exception) {
            logger.log(String.format("Failed to retrieve module registration status for module %s:\n%s",
                    model.getModuleName(), Arrays.toString(exception.getStackTrace())));
            throw new CfnGeneralServiceException(exception);
        }

        switch (dtrResponse.progressStatus()) {
            case COMPLETE:
                logger.log(String.format("Module version registration for %s with registration token %s stabilized: %s\nNew module version ARN: %s",
                        model.getModuleName(), registrationToken, dtrResponse.description(), dtrResponse.typeVersionArn()));
                if (!model.getArn().equals(dtrResponse.typeVersionArn())) {
                    logger.log(String.format("Predicted ARN of module version does not match actual ARN of module version, predicted=%s actual=%s",
                            model.getArn(), dtrResponse.typeVersionArn()));
                    model.setArn(dtrResponse.typeVersionArn());
                }
                return true;
            case IN_PROGRESS:
                logger.log(String.format("Module version registration for %s with registration token %s in progress: %s",
                        model.getModuleName(), registrationToken, dtrResponse.description()));
                return false;
            case FAILED:
                logger.log(String.format("Module version registration for %s with registration token %s failed to stabilize: %s",
                        model.getModuleName(), registrationToken, dtrResponse.description()));
                throw new CfnNotStabilizedException(ResourceModel.TYPE_NAME, model.getArn());
            default:
                logger.log(String.format("Unexpected module version registration status returned from describe request for module %s with registration token %s: %s",
                        model.getModuleName(), registrationToken, dtrResponse.description()));
                throw new CfnGeneralServiceException(String.format("received unexpected module registration status: %s", dtrResponse.progressStatus()));
        }
    }

    @Override
    protected void validateModel(final ResourceModel model) {
        if (model == null) {
            throw new CfnInvalidRequestException("ResourceModel is required");
        }
        if (model.getModulePackage() == null) {
            throw new CfnInvalidRequestException("ModulePackage is required in ResourceModel");
        }
    }
}