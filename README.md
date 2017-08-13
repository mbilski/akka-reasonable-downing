# akka-reasonable-downing #

[![Build Status](https://travis-ci.org/mbilski/akka-reasonable-downing.svg?branch=master)](https://travis-ci.org/mbilski/akka-reasonable-downing)

akka-reasonable-downing provides split brain resolver for akka cluster using static quorum strategy.

## Setup ##

Add to your `build.sbt`

```
libraryDependencies = "pl.immutables" %% "akka-reasonable-downing" % "VERSION"
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

## Contribution policy ##

Contributions via GitHub pull requests are gladly accepted from their original author. Along with
any pull requests, please state that the contribution is your original work and that you license
the work to the project under the project's open source license. Whether or not you state this
explicitly, by submitting any copyrighted material via pull request, email, or other means you
agree to license the material under the project's open source license and warrant that you have the
legal authority to do so.

## License ##

This code is open source software licensed under the
[Apache-2.0](http://www.apache.org/licenses/LICENSE-2.0) license.
