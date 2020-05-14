/*
 * Copyright 2020 Mihai Bojin
 *
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

package com.mihaibojin.props.core.resolvers;

import com.mihaibojin.props.core.Props;
import java.util.Optional;
import java.util.Set;

public interface Resolver {

  /** @return the value of the specified key, or <code>null</code> if the property is not defined */
  Optional<String> get(String key);

  /**
   * Reloads all properties managed by the implementing Resolver.
   *
   * @return a {@link Set} of all the property keys updated by the last execution of this method
   */
  Set<String> reload();

  /**
   * @return <code>true</code> if the implementation can reload its properties, or <code>false
   *     </code> if it cannot
   */
  default boolean isReloadable() {
    return true;
  }

  /**
   * @return a string identifying the resolver, if a custom id was not specified by calling {@link
   *     Props.Factory#withResolver(String, Resolver)}
   */
  String defaultId();
}