package com.xray.client.ui.viewmodel;

import com.xray.client.core.CoreManager;
import com.xray.client.domain.repository.NodeRepository;
import com.xray.client.routing.AdaptiveRoutingEngine;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata
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
public final class ConnectionViewModel_Factory implements Factory<ConnectionViewModel> {
  private final Provider<CoreManager> coreManagerProvider;

  private final Provider<AdaptiveRoutingEngine> routingEngineProvider;

  private final Provider<NodeRepository> nodeRepositoryProvider;

  public ConnectionViewModel_Factory(Provider<CoreManager> coreManagerProvider,
      Provider<AdaptiveRoutingEngine> routingEngineProvider,
      Provider<NodeRepository> nodeRepositoryProvider) {
    this.coreManagerProvider = coreManagerProvider;
    this.routingEngineProvider = routingEngineProvider;
    this.nodeRepositoryProvider = nodeRepositoryProvider;
  }

  @Override
  public ConnectionViewModel get() {
    return newInstance(coreManagerProvider.get(), routingEngineProvider.get(), nodeRepositoryProvider.get());
  }

  public static ConnectionViewModel_Factory create(Provider<CoreManager> coreManagerProvider,
      Provider<AdaptiveRoutingEngine> routingEngineProvider,
      Provider<NodeRepository> nodeRepositoryProvider) {
    return new ConnectionViewModel_Factory(coreManagerProvider, routingEngineProvider, nodeRepositoryProvider);
  }

  public static ConnectionViewModel newInstance(CoreManager coreManager,
      AdaptiveRoutingEngine routingEngine, NodeRepository nodeRepository) {
    return new ConnectionViewModel(coreManager, routingEngine, nodeRepository);
  }
}
