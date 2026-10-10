import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// Without Vitest globals, Testing Library can't register its own cleanup: unmount after each test so screens don't leak.
afterEach(() => cleanup())

// jsdom has no matchMedia; Primer's auto color mode asks for it. Report a light-mode system.
if (!window.matchMedia) {
  window.matchMedia = (query: string) =>
    ({
      matches: false,
      media: query,
      onchange: null,
      addEventListener: () => {},
      removeEventListener: () => {},
      addListener: () => {},
      removeListener: () => {},
      dispatchEvent: () => false,
    }) as MediaQueryList
}

// jsdom has no ResizeObserver either; Primer's table and nav use it to detect overflow.
const scope = globalThis as { ResizeObserver?: unknown }
if (!scope.ResizeObserver) {
  scope.ResizeObserver = class {
    observe() {}
    unobserve() {}
    disconnect() {}
  }
}
