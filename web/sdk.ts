interface Context { locale: string; visible: boolean }
interface SDK { ready: Promise<Context>; getContext(): Context; getState(): unknown; setState(value: unknown): void; onContextChanged(callback: (value: Context) => void): () => void; call<T = unknown>(method: string, args?: Record<string, unknown>): Promise<T> }
export const sdk = (window as unknown as { devline: SDK }).devline
