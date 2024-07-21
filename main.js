import './style.css'
import './style.scss'

import { parse as dotParse, SyntaxError, StartRules } from './dotParser.js';

// Make these available globally
window.DotParser = {
    parse: dotParse,
    SyntaxError: SyntaxError,
    StartRules: StartRules
};

import '@public/main.js'
