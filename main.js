import './style.css'
import './style.scss'
import { parse as dotParse, SyntaxError, StartRules } from './dotParser.js';

// Make these available globally
window.DotParser = {
    parse: dotParse,
    SyntaxError: SyntaxError,
    StartRules: StartRules
};
console.log("------- DotParser is now global ------")
console.log(window.DotParser)

// Import and run the ScalaJS main after setting up DotParser
import('@public/main.js').then(() => {
    console.log("ScalaJS main loaded and executed");
});
