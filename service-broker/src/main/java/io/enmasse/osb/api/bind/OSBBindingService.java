/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.osb.api.bind;

import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.EndpointSpec;
import io.enmasse.address.model.EndpointStatus;
import io.enmasse.api.auth.AuthApi;
import io.enmasse.api.auth.ResourceVerb;
import io.enmasse.api.common.Exceptions;
import io.enmasse.api.common.SchemaProvider;
import io.enmasse.config.AnnotationKeys;
import io.enmasse.k8s.api.AddressSpaceApi;
import io.enmasse.osb.api.EmptyResponse;
import io.enmasse.osb.api.OSBServiceBase;
import io.enmasse.user.api.UserApi;
import io.enmasse.user.model.v1.*;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.security.SecureRandom;
import java.util.*;

@Path(OSBServiceBase.BASE_URI + "/service_instances/{instanceId}/service_bindings/{bindingId}")
@Consumes({MediaType.APPLICATION_JSON})
@Produces({MediaType.APPLICATION_JSON})
public class OSBBindingService extends OSBServiceBase {

    private final UserApi userApi;
    private final Random random = new SecureRandom();

    public OSBBindingService(AddressSpaceApi addressSpaceApi, AuthApi authApi, SchemaProvider schemaProvider, UserApi userApi) {
        super(addressSpaceApi, authApi, schemaProvider);
        this.userApi = userApi;
    }

    @PUT
    public Response bindServiceInstance(@Context SecurityContext securityContext, @PathParam("instanceId") String instanceId, @PathParam("bindingId") String bindingId, BindRequest bindRequest) {
        log.info("Received bind request for instance {}, binding {} (service id {}, plan id {})",
                instanceId, bindingId, bindRequest.getServiceId(), bindRequest.getPlanId());
        verifyAuthorized(securityContext, ResourceVerb.get);
        AddressSpace addressSpace = findAddressSpaceByInstanceId(instanceId)
                .orElseThrow(() -> Exceptions.notFoundException("Service instance " + instanceId + " does not exist"));

        Map<String, String> parameters = bindRequest.getParameters();

        String username = "user-" + bindingId;
        byte[] passwordBytes = new byte[32];
        this.random.nextBytes(passwordBytes);
        String password = Base64.getEncoder().encodeToString(passwordBytes);

        UserSpec.Builder specBuilder = new UserSpec.Builder();
        specBuilder.setUsername(username);
        specBuilder.setAuthentication(new UserAuthentication.Builder()
                .setType(UserAuthenticationType.password)
                .setPassword(password)
                .build());


        List<UserAuthorization> authorizations = new ArrayList<>();

        authorizations.add(new UserAuthorization.Builder()
                .setOperations(Arrays.asList(Operation.send))
                .setAddresses(getAddresses(parameters.get("sendAddresses")))
                .build());

        authorizations.add(new UserAuthorization.Builder()
                .setOperations(Arrays.asList(Operation.recv))
                .setAddresses(getAddresses(parameters.get("receiveAddresses")))
                .build());

        if(parameters.containsKey("consoleAccess")
                && Boolean.valueOf(parameters.get("consoleAccess"))) {
            authorizations.add(new UserAuthorization.Builder()
                    .setOperations(Arrays.asList(Operation.view))
                    .setAddresses(Arrays.asList("*"))
                    .build());
        }

        if(parameters.containsKey("consoleAdmin")
                && Boolean.valueOf(parameters.get("consoleAdmin"))) {
            authorizations.add(new UserAuthorization.Builder()
                    .setOperations(Arrays.asList(Operation.manage))
                    .build());
        }

        specBuilder.setAuthorization(authorizations);


        User user = new User.Builder()
            .setMetadata(new UserMetadata.Builder()
                    .setNamespace(addressSpace.getNamespace())
                    .setName(username)
                    .build())
                        .setSpec(specBuilder.build())
                        .build();
        try {

            userApi.createUser(addressSpace.getAnnotation(AnnotationKeys.REALM_NAME), user);

            Map<String, String> credentials = new LinkedHashMap<>();
            credentials.put("username", username);
            credentials.put("password", password);
            if ((parameters.containsKey("consoleAccess") && Boolean.valueOf(parameters.get("consoleAccess"))) ||
                    (parameters.containsKey("consoleAdmin") && Boolean.valueOf(parameters.get("consoleAdmin")))) {
                addressSpace.getEndpoints().stream().filter(e -> e.getName().equals("console")).findFirst().ifPresent(e -> {
                    e.getHost().ifPresent(h -> credentials.put("console", "https://" + h));
                });
            }

            for (EndpointSpec endpointSpec : addressSpace.getEndpoints()) {
                if ("console".equals(endpointSpec.getService())) {
                    continue;
                }
                String prefix = endpointSpec.getName();

                EndpointStatus endpointStatus = null;
                for (EndpointStatus status : addressSpace.getStatus().getEndpointStatuses()) {
                    if (status.getName().equals(endpointSpec.getName())) {
                        endpointStatus = status;
                        break;
                    }
                }
                if (endpointStatus == null) {
                    continue;
                }

                if (parameters.containsKey("externalAccess") && Boolean.valueOf(parameters.get("externalAccess")) && endpointStatus.getHost() != null) {
                    String externalPrefix = "external" + prefix.substring(0, 1).toUpperCase() + prefix.substring(1);
                    credentials.put(externalPrefix + "Host", endpointStatus.getHost());
                    credentials.put(externalPrefix + "Port", String.format("%d", endpointStatus.getPort()));
                }
                credentials.put(prefix + "Host", endpointStatus.getServiceHost());
                for (Map.Entry<String, Integer> servicePort : endpointStatus.getServicePorts().entrySet()) {
                    String portName = servicePort.getKey().substring(0, 1).toUpperCase() + servicePort.getKey().substring(1);
                    credentials.put(prefix + portName + "Port", String.format("%d", servicePort.getValue()));
                }
                endpointSpec.getCertSpec().ifPresent(certSpec -> {
                    String cert = getAuthApi().getCert(certSpec.getSecretName(), addressSpace.getAnnotation(AnnotationKeys.NAMESPACE));
                    credentials.put(prefix + "Cert.pem", cert);
                });
            }
            return Response.status(Response.Status.CREATED).entity(new BindResponse(credentials)).build();

        } catch (Exception e) {
            throw new InternalServerErrorException("Exception interacting with auth service", e);
        }

 // TODO: return 200 OK, when binding already exists

    }

    private Collection<String> getAddresses(String addressList) {
        Set<String> groups = new HashSet<>();
        if(addressList != null) {
            for(String address : addressList.split(",")) {
                address = address.trim();
                if(address.length()>0) {
                    groups.add(address);
                }
            }
        }
        return groups;
    }

    @DELETE
    public Response unbindServiceInstance(@Context SecurityContext securityContext, @PathParam("instanceId") String instanceId, @PathParam("bindingId") String bindingId) {
        log.info("Received unbind request for instance {}, binding {}", instanceId, bindingId);
        verifyAuthorized(securityContext, ResourceVerb.get);
/*
        AddressSpace addressSpace = findAddressSpaceByAddressUuid(instanceId)
                .orElseThrow(() -> OSBExceptions.notFoundException("Service instance " + instanceId + " does not exist"));
*/

        return Response.ok(new EmptyResponse()).build();
    }

}
