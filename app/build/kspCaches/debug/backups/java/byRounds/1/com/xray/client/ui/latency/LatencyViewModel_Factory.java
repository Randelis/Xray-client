package com.xray.client.ui.latency;

import com.xray.client.domain.repository.NodeRepository;
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
public final class LatencyViewModel_Factory implements Factory<LatencyViewModel> {
  private final Provider<NodeRepository> nodeRepositoryProvider;

  public LatencyViewModel_Factory(Provider<NodeRepository> nodeRepositoryProvider) {
    this.nodeRepositoryProvider = nodeRepositoryProvider;
  }

  @Override
  public LatencyViewModel get() {
    return newInstance(nodeRepositoryProvider.get());
  }

  public static LatencyViewModel_Factory create(Provider<NodeRepository> nodeRepositoryProvider) {
    return new LatencyViewModel_Factory(nodeRepositoryProvider);
  }

  public static LatencyViewModel newInstance(NodeRepository nodeRepository) {
    return new LatencyViewModel(nodeRepository);
  }
}
