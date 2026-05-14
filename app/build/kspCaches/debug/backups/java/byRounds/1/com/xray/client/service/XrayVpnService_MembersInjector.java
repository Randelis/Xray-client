package com.xray.client.service;

import com.xray.client.routing.AdaptiveRoutingEngine;
import dagger.MembersInjector;
import dagger.internal.DaggerGenerated;
import dagger.internal.InjectedFieldSignature;
import dagger.internal.QualifierMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@QualifierMetadata
@DaggerGenerated
@Generated(
    value = "dagger.internal.codegen.ComponentProcessor",
    comments = "https://dagger.dev"
)
@SuppressWarnings({
    "unchecked",
    "rawtypes",
    "KotlinInternal",
    "KotlinInternalInJava",
    "cast",
    "deprecation"
})
public final class XrayVpnService_MembersInjector implements MembersInjector<XrayVpnService> {
  private final Provider<AdaptiveRoutingEngine> routingEngineProvider;

  public XrayVpnService_MembersInjector(Provider<AdaptiveRoutingEngine> routingEngineProvider) {
    this.routingEngineProvider = routingEngineProvider;
  }

  public static MembersInjector<XrayVpnService> create(
      Provider<AdaptiveRoutingEngine> routingEngineProvider) {
    return new XrayVpnService_MembersInjector(routingEngineProvider);
  }

  @Override
  public void injectMembers(XrayVpnService instance) {
    injectRoutingEngine(instance, routingEngineProvider.get());
  }

  @InjectedFieldSignature("com.xray.client.service.XrayVpnService.routingEngine")
  public static void injectRoutingEngine(XrayVpnService instance,
      AdaptiveRoutingEngine routingEngine) {
    instance.routingEngine = routingEngine;
  }
}
