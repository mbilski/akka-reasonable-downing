# akka-reasonable-downing #

[![Build Status](https://travis-ci.org/mbilski/akka-reasonable-downing.svg?branch=master)](https://travis-ci.org/mbilski/akka-reasonable-downing)
[![codecov](https://codecov.io/gh/mbilski/akka-reasonable-downing/branch/master/graph/badge.svg)](https://codecov.io/gh/mbilski/akka-reasonable-downing)

akka-reasonable-downing provides split brain resolver for akka static cluster using quorum strategy.

## Setup ##

Add to your `build.sbt`

```
resolvers += Resolver.jcenterRepo

libraryDependencies += "pl.immutables" %% "akka-reasonable-downing" % "0.0.4"
```

## Configuration ##

```
akka {
  cluster {
    downing-provider-class = "pl.immutables.akka.reasonable.downing.StaticQuorumDowningProvider"
    min-nr-of-members = ${akka.reasonable.downing.quorum-size}
  }

  reasonable.downing {
    # the time to make the decision after the cluster is stable
    stable-after = 7 seconds

    # the N / 2 + 1 where N is number of nodes in a static cluster
    quorum-size = 3
  }
}
```

## Demo ##

See the demo [here](https://www.youtube.com/watch?v=_uz8QOjVrNQ).

## License ##

This code is open source software licensed under the
[Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0) license.
