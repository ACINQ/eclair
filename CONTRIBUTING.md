# Contributing to Eclair

Eclair welcomes contributions in the form of peer review, testing and patches.
This document explains the practical process and guidelines for contributing.

While developing a Lightning implementation is an exciting project that spans many domains
(cryptography, peer-to-peer networking, databases, etc), contributors must keep in mind that this
represents real money and introducing bugs or security vulnerabilities can have far more dire
consequences than in typical projects. In the world of cryptocurrencies, even the smallest bug in
the wrong area can cost users a significant amount of money.

If you're looking for somewhere to start contributing, check out the [good first issue](https://github.com/acinq/eclair/issues?q=is%3Aopen+is%3Aissue+label%3A"good+first+issue") list.

Another way to start contributing is by adding tests or improving them.
This will help you understand the different parts of the codebase and how they work together.

## Communicating

We recommend using our Gitter [developers channel](https://gitter.im/ACINQ/developers).
Introducing yourself and explaining what you'd like to work on is always a good idea: you will get
some pointers and feedback from experienced contributors. It will also ensure that you're not
duplicating work that someone else is doing.

We use Github issues only for, well, issues (mostly bugs that need to be investigated).
You can also use Github issues for [feature requests](https://github.com/acinq/eclair/issues?q=is%3Aissue+label%3A"feature+request").

## Recommended Reading

- [Bitcoin Whitepaper](https://bitcoin.org/bitcoin.pdf)
- [Lightning Network Whitepaper](https://lightning.network/lightning-network-paper.pdf)
- [Deployable Lightning](https://github.com/ElementsProject/lightning/raw/master/doc/deployable-lightning.pdf)
- [Understanding the Lightning Network](https://bitcoinmagazine.com/articles/understanding-the-lightning-network-part-building-a-bidirectional-payment-channel-1464710791)
- [Lightning Network Specification](https://github.com/lightningnetwork/lightning-rfc)
- [High Level Lightning Network Specification](https://medium.com/@rusty_lightning/the-bitcoin-lightning-spec-part-1-8-a7720fb1b4da)

## Recommended Skillset

Eclair uses [Scala](https://www.scala-lang.org/) and [Akka](https://akka.io/).
Some experience with these technologies is required to contribute.
There are a lot of good resources online to learn about them.

## Contributor Workflow

To contribute a patch, the workflow is as follows:

1. [Fork repository](https://help.github.com/en/github/getting-started-with-github/fork-a-repo) (only the first time)
2. Create a topic branch
3. Add commits
4. Open a pull request

### Pull Request Philosophy

Pull requests should always be focused. For example, a pull request could add a feature, fix a bug,
or refactor code; but not a mixture.
Please also avoid super pull requests which attempt to do too much, are overly large, or overly
complex as this makes review difficult.

You should try your best to make reviewers' lives as easy as possible: a lot more time will be
spent reading your code than the time you spent writing it.
The quicker your changes are merged to master, the less time you will need to spend rebasing and
otherwise trying to keep up with the master branch.

Pull request should always include a clean, detailed description of what they fix/improve, why,
and how.
Even if you think that it is obvious, don't be shy and add explicit details and explanations.

When fixing a bug, please start by adding a failing test that reproduces the issue.
Create a first commit containing that test without the fix: this makes it easy to verify that the
test correctly failed. You can then fix the bug in additional commits.

When adding a new feature, thought must be given to the long term technical debt and maintenance
that feature may require after inclusion. Before proposing a new feature that will require
maintenance, please consider if you are willing to maintain it (including bug fixing).

When addressing pull request comments, we recommend using [fixup commits](https://robots.thoughtbot.com/autosquashing-git-commits).
The reason for this is two fold: it makes it easier for the reviewer to see what changes have been
made between versions (since Github doesn't easily show prior versions) and it makes it easier on
the PR author as they can set it to auto-squash the fixup commits on rebase.

It's recommended to take great care in writing tests and ensuring the entire test suite has a
stable successful outcome; eclair uses continuous integration techniques and having a stable build
helps the reviewers with their job.

We don't have hard rules around code style, but we do avoid having too many conflicting styles;
look around and make sure you code fits well with the rest of the codebase.

### Signed Commits

We ask contributors to sign their commits.
You can find setup instructions [here](https://help.github.com/en/github/authenticating-to-github/signing-commits).

### Commit Message

Eclair keeps a clean commit history on the master branch with well-formed commit messages.

Here is a model Git commit message:

```text
Short (50 chars or less) summary of changes

More detailed explanatory text, if necessary. Wrap it to about 72
characters or so. In some contexts, the first line is treated as the
subject of an email and the rest of the text as the body. The blank
line separating the summary from the body is critical (unless you omit
the body entirely); tools like rebase can get confused if you run the
two together.

Write your commit message in the present tense: "Fix bug" and not
"Fixed bug". This convention matches up with commit messages generated
by commands like git merge and git revert.

Further paragraphs come after blank lines.

- Bullet points are okay, too
- Typically a hyphen or asterisk is used for the bullet, preceded by a
  single space, with blank lines in between, but conventions vary here
- Use a hanging indent
```

### Dependencies

We try to minimize our dependencies (libraries and tools). Introducing new dependencies increases
package size, attack surface and cognitive overhead.

Since Eclair is [running on Android](https://github.com/acinq/eclair-mobile), we have a requirement
to be compatible with Java 7. This currently restricts the set of dependencies we can add and the
language features we use.

If your contribution is adding a new dependency, please detail:

- why you need it
- why you chose this specific library/tool (a thorough analysis of alternatives will be
  appreciated)

Contributions that add new dependencies may take longer to approve because a detailed audit of the
dependency may be required.

### IntelliJ Tips

If you're using [IntelliJ](https://www.jetbrains.com/idea/), here are some useful commands:

- Ctrl+Alt+L: format file (ensures consistency in the codebase)
- Ctrl+Alt+o: optimize imports (removes unused imports)

Note that we use IntelliJ's default formatting configuration for Scala to minimize conflicts.

### Contribution Checklist

- The code being submitted is accompanied by tests which exercise both the positive and negative
  (error paths) conditions (if applicable)
- The code being submitted is correctly formatted
- The code being submitted has a clean, easy-to-follow commit history
- All commits are signed
