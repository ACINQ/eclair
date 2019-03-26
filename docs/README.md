# Eclair documentation man page

- How to build

## Building

The documentation uses [slate](https://github.com/lord/slate) and is hosted via github pages in this repo. To get started you need to download _slate_ in your
local machine, you need ruby, ruby-bundle and a few other dependencies. For the complete instructions on how to run slate please refer to its official
documentation [here](https://github.com/lord/slate#getting-set-up).

:warning: On ubuntu 18.10  you have to **append** `gem "therubyracer"` to **Gemfile** :warning:

### Modifying eclair doc

1. Copy recursively `eclair/docs/slate` into `slate/source` and open a text editor at `slate/source/index.html.md`
2. Make your changes
3. From within the slate folder run `bundle exec middleman build --clean` as specified by the [instructions](https://github.com/lord/slate/wiki/Deploying-Slate#publishing-your-docs-to-your-own-server)
4. Copy the content of `slate/build` into `eclair/docs`

