package com.xray.client.routing;

import android.content.Context;
import com.xray.client.core.CoreManager;
import dagger.internal.DaggerGenerated;
import dagger.internal.Factory;
import dagger.internal.QualifierMetadata;
import dagger.internal.ScopeMetadata;
import javax.annotation.processing.Generated;
import javax.inject.Provider;

@ScopeMetadata("javax.inject.Singleton")
@QualifierMetadata("dagger.hilt.android.qualifiers.ApplicationContext")
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
public final class AdaptiveRoutingEngine_Factory implements Factory<AdaptiveRoutingEngine> {
  private final Provider<Context> contextProvider;

  private final Provider<CoreManager> coreManagerProvider;

  public AdaptiveRoutingEngine_Factory(Provider<Context> contextProvider,
      Provider<CoreManager> coreManagerProvider) {
    this.contextProvider = contextProvider;
    this.coreManagerProvider = coreManagerProvider;
  }

  @Override
  public AdaptiveRoutingEngine get() {
    return newInstance(contextProvider.get(), coreManagerProvider.get());
  }

  public static AdaptiveRoutingEngine_Factory create(Provider<Context> contextProvider,
      Provider<CoreManager> coreManagerProvider) {
    return new AdaptiveRoutingEngine_Factory(contextProvider, coreManagerProvider);
  }

  public static AdaptiveRoutingEngine newInstance(Context context, CoreManager coreManager) {
    return new AdaptiveRoutingEngine(context, coreManager);
  }
}
