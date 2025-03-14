/** @type {import('tailwindcss').Config} */
module.exports = {
    important: true,
    content: [
        './viewer/target/scala-*/**/main.js'
    ],
    theme: {
        extend: {},
    },

    plugins: [
        require("@tailwindcss/typography"),
    ]
}
