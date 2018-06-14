# akka-reasonable-downing #

[![Build Status](https://travis-ci.org/mbilski/akka-reasonable-downing.svg?branch=master)](https://travis-ci.org/mbilski/akka-reasonable-downing)
[![codecov](https://codecov.io/gh/mbilski/akka-reasonable-downing/branch/master/graph/badge.svg)](https://codecov.io/gh/mbilski/akka-reasonable-downing)
[![Maven Central](https://maven-badges.herokuapp.com/maven-central/pl.immutables/akka-reasonable-downing_2.12/badge.svg)](https://maven-badges.herokuapp.com/maven-central/pl.immutables/akka-reasonable-downing_2.12)

akka-reasonable-downing provides split brain resolver for static akka cluster using quorum strategy.

## Setup ##

Add to your `build.sbt`

```
libraryDependencies += "pl.immutables" %% "akka-reasonable-downing" % "1.1.0"
```

## Configuration ##

```
akka {
  cluster {
    downing-provider-class = "pl.immutables.akka.reasonable.downing.StaticQuorumDowningProvider"
    min-nr-of-members = ${akka.reasonable.downing.quorum-size}
  }

  reasonable.downing {
    # the time to make a decision after the cluster is stable
    stable-after = 7 seconds

    # the N / 2 + 1 where N is number of nodes in a static cluster
    quorum-size = 3

    # list of the roles which be used in quorum. may be empty or absent.
    quorum-roles = ["seed"]
  }
}
```

## Demo ##

See the demo [here](https://www.youtube.com/watch?v=_uz8QOjVrNQ).

## License ##

This code is open source software licensed under the
[Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0) license.
