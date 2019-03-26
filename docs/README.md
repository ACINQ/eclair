# Eclair documentation man page

- How to build

## Building

The documentation uses [slate](https://github.com/lord/slate) and is hosted via github pages in this repo. To get started you need to download _slate_ in your
local machine, you'll need ruby, ruby-bundle and a few other dependencies. For the complete instructions on how to run slate please refer to its official
documentation [here](https://github.com/lord/slate#getting-set-up).

:warning: On ubuntu 18.10  you have to **append** `gem "therubyracer"` to **Gemfile** :warning:

Slate is a tool that converts markdown files into HTML, the documentation is all inside `eclair/docs/source/index.html.md`. You typically run slate from within the slate
project (see https://github.com/lord/slate) where ruby-bundle can find the executables. This also means that slate will work on whatever finds in the
`slate/source` folder and its output goes in `slate/build`, after you've updated and built the API doc don't forget to copy the updated slate markdown
back to the eclair doc folder.


### Modifying eclair doc

1. Copy recursively `eclair/docs/source` into `slate/source` and open a text editor at `slate/source/index.html.md`
2. Make your changes
3. From within the slate folder run `bundle exec middleman build --clean` as specified by the [instructions](https://github.com/lord/slate/wiki/Deploying-Slate#publishing-your-docs-to-your-own-server)
4. Copy the content of `slate/build` into `eclair/docs` and `slate/source/index.html.md` into `eclair/docs/source/index.html.md`

