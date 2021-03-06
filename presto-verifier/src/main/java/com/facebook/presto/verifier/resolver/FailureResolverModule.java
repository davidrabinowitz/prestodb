/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.verifier.resolver;

import com.facebook.airlift.configuration.AbstractConfigurationAwareModule;
import com.google.common.collect.ImmutableList;
import com.google.inject.Binder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.facebook.airlift.configuration.ConfigBinder.configBinder;
import static com.google.inject.Scopes.SINGLETON;
import static com.google.inject.multibindings.Multibinder.newSetBinder;
import static com.google.inject.name.Names.named;
import static java.util.Objects.requireNonNull;

public class FailureResolverModule
        extends AbstractConfigurationAwareModule
{
    public static final FailureResolverModule BUILT_IN = FailureResolverModule.builder()
            .bind(ExceededGlobalMemoryLimitFailureResolver.NAME, ExceededGlobalMemoryLimitFailureResolver.class, Optional.empty())
            .bind(ExceededTimeLimitFailureResolver.NAME, ExceededTimeLimitFailureResolver.class, Optional.empty())
            .bind(VerifierLimitationFailureResolver.NAME, VerifierLimitationFailureResolver.class, Optional.empty())
            .bindFactory(TooManyOpenPartitionsFailureResolver.NAME, TooManyOpenPartitionsFailureResolver.Factory.class, Optional.of(TooManyOpenPartitionsFailureResolverConfig.class))
            .build();

    private final List<FailureResolverBinding> resolvers;
    private final List<FailureResolverFactoryBinding> factories;

    private FailureResolverModule(
            List<FailureResolverBinding> resolvers,
            List<FailureResolverFactoryBinding> factories)
    {
        this.resolvers = ImmutableList.copyOf(resolvers);
        this.factories = ImmutableList.copyOf(factories);
    }

    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(FailureResolverConfig.class);
        binder.bind(FailureResolverManagerFactory.class).in(SINGLETON);
        if (!buildConfigObject(FailureResolverConfig.class).isEnabled()) {
            return;
        }

        for (FailureResolverBinding binding : resolvers) {
            bind(binder, binding.getName(), Optional.of(binding.getResolverClass()), Optional.empty(), binding.getConfigClass());
        }
        for (FailureResolverFactoryBinding binding : factories) {
            bind(binder, binding.getName(), Optional.empty(), Optional.of(binding.getFactoryClass()), binding.getConfigClass());
        }
    }

    private void bind(
            Binder binder,
            String name,
            Optional<Class<? extends FailureResolver>> failureResolverClass,
            Optional<Class<? extends FailureResolverFactory>> failureResolverFactoryClass,
            Optional<Class<?>> failureResolverConfigClass)
    {
        configBinder(binder).bindConfig(FailureResolverConfig.class, named(name), name);
        if (buildConfigObject(FailureResolverConfig.class, name).isEnabled()) {
            failureResolverClass.ifPresent(clazz -> newSetBinder(binder, FailureResolver.class).addBinding().to(clazz));
            failureResolverFactoryClass.ifPresent(clazz -> newSetBinder(binder, FailureResolverFactory.class).addBinding().to(clazz));
            failureResolverConfigClass.ifPresent(clazz -> configBinder(binder).bindConfig(clazz, name));
        }
    }

    public static Builder builder()
    {
        return new Builder();
    }

    public static class Builder
    {
        private final List<FailureResolverBinding> resolvers = new ArrayList<>();
        private final List<FailureResolverFactoryBinding> factories = new ArrayList<>();

        public Builder bind(String name, Class<? extends FailureResolver> failureResolverClass, Optional<Class<?>> configClass)
        {
            resolvers.add(new FailureResolverBinding(name, failureResolverClass, configClass));
            return this;
        }

        public Builder bindFactory(String name, Class<? extends FailureResolverFactory> failureResolverFactoryClass, Optional<Class<?>> configClass)
        {
            factories.add(new FailureResolverFactoryBinding(name, failureResolverFactoryClass, configClass));
            return this;
        }

        public FailureResolverModule build()
        {
            return new FailureResolverModule(resolvers, factories);
        }
    }

    private static class FailureResolverBinding
            extends AbstractFailureResolverBinding
    {
        private final Class<? extends FailureResolver> resolverClass;

        public FailureResolverBinding(String name, Class<? extends FailureResolver> resolverClass, Optional<Class<?>> configClass)
        {
            super(name, configClass);
            this.resolverClass = resolverClass;
        }

        public Class<? extends FailureResolver> getResolverClass()
        {
            return resolverClass;
        }
    }

    private static class FailureResolverFactoryBinding
            extends AbstractFailureResolverBinding
    {
        private final Class<? extends FailureResolverFactory> factoryClass;

        public FailureResolverFactoryBinding(String name, Class<? extends FailureResolverFactory> factoryClass, Optional<Class<?>> configClass)
        {
            super(name, configClass);
            this.factoryClass = factoryClass;
        }

        public Class<? extends FailureResolverFactory> getFactoryClass()
        {
            return factoryClass;
        }
    }

    private abstract static class AbstractFailureResolverBinding
    {
        private final String name;
        private final Optional<Class<?>> configClass;

        public AbstractFailureResolverBinding(String name, Optional<Class<?>> configClass)
        {
            this.name = requireNonNull(name, "name is null");
            this.configClass = requireNonNull(configClass, "configClass is null");
        }

        public String getName()
        {
            return name;
        }

        public Optional<Class<?>> getConfigClass()
        {
            return configClass;
        }
    }
}
