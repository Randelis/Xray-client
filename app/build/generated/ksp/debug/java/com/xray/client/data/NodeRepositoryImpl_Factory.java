package com.xray.client.data;

import android.content.Context;
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
public final class NodeRepositoryImpl_Factory implements Factory<NodeRepositoryImpl> {
  private final Provider<Context> contextProvider;

  public NodeRepositoryImpl_Factory(Provider<Context> contextProvider) {
    this.contextProvider = contextProvider;
  }

  @Override
  public NodeRepositoryImpl get() {
    return newInstance(contextProvider.get());
  }

  public static NodeRepositoryImpl_Factory create(Provider<Context> contextProvider) {
    return new NodeRepositoryImpl_Factory(contextProvider);
  }

  public static NodeRepositoryImpl newInstance(Context context) {
    return new NodeRepositoryImpl(context);
  }
}
