import { JSDOM } from 'jsdom';

const dom = new JSDOM('<!doctype html><html><body></body></html>', {
  url: 'http://localhost/',
});

const g = globalThis;

g.window = dom.window;
g.document = dom.window.document;
g.DOMParser = dom.window.DOMParser;
g.Node = dom.window.Node;
g.Element = dom.window.Element;
g.HTMLElement = dom.window.HTMLElement;
g.SVGElement = dom.window.SVGElement;
if (typeof g.navigator === 'undefined') {
  Object.defineProperty(g, 'navigator', {
    value: dom.window.navigator,
    configurable: true,
    writable: true,
  });
}

// Defensive DOMPurify bootstrap for environments where modules were loaded
// before a window object existed.
try {
  const dpModule = await import('dompurify');
  const DOMPurify = dpModule.default || dpModule;
  if (typeof DOMPurify?.sanitize !== 'function' && typeof DOMPurify === 'function') {
    const instance = DOMPurify(dom.window);
    Object.assign(DOMPurify, instance);
  }
} catch {
  // Best-effort preload only.
}
