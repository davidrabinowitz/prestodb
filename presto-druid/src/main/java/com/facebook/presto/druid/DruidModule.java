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
package com.facebook.presto.druid;

import com.google.inject.Binder;
import com.google.inject.Module;
import com.google.inject.Scopes;

import static com.facebook.airlift.configuration.ConfigBinder.configBinder;
import static com.facebook.airlift.http.client.HttpClientBinder.httpClientBinder;

public class DruidModule
        implements Module
{
    public DruidModule()
    {
    }

    @Override
    public void configure(Binder binder)
    {
        binder.bind(DruidConnector.class).in(Scopes.SINGLETON);
        binder.bind(DruidMetadata.class).in(Scopes.SINGLETON);
        binder.bind(DruidHandleResolver.class).in(Scopes.SINGLETON);
        binder.bind(DruidClient.class).in(Scopes.SINGLETON);
        binder.bind(DruidConnectorPlanOptimizer.class).in(Scopes.SINGLETON);
        binder.bind(DruidSplitManager.class).in(Scopes.SINGLETON);
        binder.bind(DruidPageSourceProvider.class).in(Scopes.SINGLETON);
        binder.bind(DruidQueryGenerator.class).in(Scopes.SINGLETON);

        configBinder(binder).bindConfig(DruidConfig.class);
        httpClientBinder(binder).bindHttpClient("druid-client", ForDruidClient.class);
    }
}
