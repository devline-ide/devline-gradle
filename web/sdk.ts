import type { DevLineWebviewContext, DevLineWebviewSdk } from '@devline/extension-devkit'
type Context = DevLineWebviewContext
type SDK = DevLineWebviewSdk<Context>
export const sdk = (window as unknown as { devline: SDK }).devline
