/*
 * Copyright 2018, EnMasse authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.enmasse.controller.auth;

import io.enmasse.address.model.AddressSpace;
import io.enmasse.address.model.CertSpec;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.Collections;

import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertThat;

public class WildcardCertProviderTest {
    @Rule
    public KubernetesServer server = new KubernetesServer(true, true);

    private KubernetesClient client;
    private CertProvider certProvider;

    @Before
    public void setup() {
        client = server.getClient();
        CertSpec spec = new CertSpec("wildcard", "mycerts");
        String wildcardCert = "wildcardcert";

        certProvider = new WildcardCertProvider(client, spec, wildcardCert);
    }

    @Test(expected = IllegalStateException.class)
    public void testUnknownWildcardSecret() {

        AddressSpace space = new AddressSpace.Builder()
                .setName("myspace")
                .setType("standard")
                .setPlan("myplan")
                .build();

        certProvider.provideCert(space, "messaging.example.com", Collections.emptySet());
    }

    @Test
    public void testProvideCert() {

        AddressSpace space = new AddressSpace.Builder()
                .setName("myspace")
                .setPlan("myplan")
                .setType("standard")
                .build();

        client.secrets().create(new SecretBuilder()
                .editOrNewMetadata()
                .withName("wildcardcert")
                .endMetadata()
                .addToData("tls.key", "mykey")
                .addToData("tls.crt", "myvalue")
                .build());

        certProvider.provideCert(space, "messaging.example.com", Collections.emptySet());

        Secret cert = client.secrets().withName("mycerts").get();
        assertThat(cert.getData().get("tls.key"), is("mykey"));
        assertThat(cert.getData().get("tls.crt"), is("myvalue"));
    }
}
