# props

This project aims to provide a library which manages properties and secrets coming from
multiple sources (startup properties, environment, property files, etc.)

While `props` is currently implemented in JAVA, additional languages are planned in the future.

![](https://github.com/MihaiBojin/props/workflows/Deploy%20to%20Maven%20Central/badge.svg)
![.github/workflows/ci.yml](https://github.com/MihaiBojin/props/workflows/.github/workflows/ci.yml/badge.svg?branch=master&event=push)

# Features

- Simple and straightforward API
- Optimized for reads, property values are cached and updated when changed
- No third-party dependencies in the core module
- Periodically refresh properties from sources
- Thread-safety
- Ability to specify the order in which sources are queried for values


# Performance

While `props` is quite fast, it was not built for low-latency applications.
You can read more about this topic on the [benchmark results page](java/benchmark/README.md). 

# Contributing to the project

Please see the [contributor guide](./CONTRIBUTING.md).


# License

Copyright 2020 Mihai Bojin

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
