/** @type {import('tailwindcss').Config} */
module.exports = {
    important: true,
    content: [
        './viewer/target/scala-*/**/main.js'
    ],
    theme: {
        extend: {},
    },

    daisyui: {
        themes: ["light", "dark", "cupcake", "pastel", "nord", "lemonade", "autumn"],
    },

    plugins: [
        require("@tailwindcss/typography"),
        require("daisyui")
    ],
}
