# Type Explorer

## Install

You need to explicitly install the following software:

* sbt, as part of [getting started with Scala](https://docs.scala-lang.org/getting-started/index.html) (or if you prefer, through [its standalone download](https://www.scala-sbt.org/download.html))
* [Node.js](https://nodejs.org/en/)

Other software will be downloaded automatically by the commands below.

## Prepare

Before doing anything, including before importing in an IDE, run

```
$ npm install
```

## Development

### Running the UI

Open two terminals.
In the first one, start `sbt` and, within, continuously build the Scala.js project:

```
$ sbt
> ~ viewer/fastLinkJS
```

In the second one, start the Vite development server with

```
$ npm run dev
```

Follow the URL presented to you by Vite to open the application.

You can now continuously edit files in the subproject `UI`, for example the `MainJS.scala` file, and Vite will automatically reload the page on save.

## Production build

Make a production build with

```
sbt "viewer/fullLinkJS"
$ npm run build
```

You can then find the built files in the `dist/` directory.
You will need an HTTP server, such as `python3 -m http.server 8000`, to open the files, as Vite rewrites `<script>` tags to prevent cross-origin requests.


# License

[License](./LICENSE)

Copyright 2023 Juan Pablo Romero and the type-explorer contributors.
