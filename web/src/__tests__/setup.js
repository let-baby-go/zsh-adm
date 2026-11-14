// Browser APIs Arco components use that jsdom does not provide.
globalThis.ResizeObserver ??= class { observe() {} unobserve() {} disconnect() {} }
window.matchMedia ??= query => ({ matches: false, media: query, addEventListener() {}, removeEventListener() {}, addListener() {}, removeListener() {} })
