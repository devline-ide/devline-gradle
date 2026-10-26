import { computed, onUnmounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { sdk } from './sdk'
interface Project { path: string; name: string; parentPath?: string }
interface Task { path: string; name: string; projectPath: string; group?: string; description?: string }
interface Operation { id: string; kind: string; status: string; error?: string }
interface Overview { available: boolean; rootPath?: string; rootName?: string; wrapper: boolean; fallbackVersion: string; selectionSource: string; synced: boolean; operation?: Operation; projects: Project[]; tasks: Task[]; nextTaskOffset: number; tasksTotal: number; nextProjectOffset: number; projectsTotal: number; output?: {cursor: number; text: string}[]; nextCursor?: number; truncated?: boolean; hasMoreOutput?: boolean }
export function useGradle() {
  const { t, te, locale } = useI18n(), context = ref(sdk.getContext()), data = ref<Overview>(), error = ref(''), pending = ref(false)
  const saved = sdk.getState() as { project?: string; query?: string } | undefined
  const project = ref(saved?.project ?? ''), query = ref(saved?.query ?? ''), rootPath = ref(''), linking = ref(false), output = ref(''), cursor = ref(0)
  const active = computed(() => ['running', 'cancelling'].includes(data.value?.operation?.status ?? ''))
  const tasks = computed(() => (data.value?.tasks ?? []).filter(task => !project.value || task.projectPath === project.value))
  const groups = computed(() => [...new Set(tasks.value.map(task => task.group || t('other')))].sort().map(name => ({ name, tasks: tasks.value.filter(task => (task.group || t('other')) === name) })))
  let disposed = false, timer: ReturnType<typeof setTimeout> | undefined, searchTimer: ReturnType<typeof setTimeout> | undefined, epoch = 0
  function report(cause: unknown) { const code = cause instanceof Error ? cause.message : ''; error.value = te(code) ? t(code) : code || t('failed') }
  function schedule() { clearTimeout(timer); if (!disposed && context.value.visible && (active.value || data.value?.hasMoreOutput)) timer = setTimeout(() => { void refresh(false, true) }, 650) }
  function accept(value: Overview, append = false, retainModel = false) {
    if (data.value?.operation?.id !== value.operation?.id || data.value?.rootPath !== value.rootPath) { output.value = ''; cursor.value = 0 }
    if (value.output?.length) output.value = (output.value + value.output.map(item => item.text).join('')).slice(-120000)
    if (value.nextCursor !== undefined) cursor.value = value.nextCursor
    if (value.truncated) output.value = t('truncated') + '\n' + output.value
    if (append && data.value) { value.tasks = [...data.value.tasks, ...value.tasks]; value.projects = [...data.value.projects, ...value.projects.filter(item => !data.value!.projects.some(old => old.path === item.path))] }
    if (retainModel && !(value.operation?.kind === 'sync' && value.operation.status === 'succeeded' && data.value?.operation?.status !== 'succeeded') && data.value?.synced && value.synced && data.value.rootPath === value.rootPath) { value.tasks = data.value.tasks; value.projects = data.value.projects; value.nextTaskOffset = data.value.nextTaskOffset; value.nextProjectOffset = data.value.nextProjectOffset }
    data.value = value; schedule()
  }
  async function refresh(append = false, retainModel = false) {
    if (disposed || !context.value.visible || pending.value) return
    const generation = epoch, filter = project.value + '\0' + query.value; pending.value = true; error.value = ''
    try {
      const value = await sdk.call<Overview>('state', { cursor: cursor.value, taskQuery: query.value, taskProject: project.value, taskOffset: append ? data.value?.nextTaskOffset ?? 0 : 0, projectOffset: append ? data.value?.nextProjectOffset ?? 0 : 0 })
      if (!disposed && epoch === generation && filter === project.value + '\0' + query.value) accept(value, append, retainModel)
    } catch (cause) { if (!disposed && epoch === generation) report(cause) }
    finally { pending.value = false; if (!disposed && filter !== project.value + '\0' + query.value) void refresh(); else schedule() }
  }
  async function action(method: string, args: Record<string, unknown> = {}) {
    if (pending.value || disposed) return
    pending.value = true; error.value = ''; clearTimeout(timer)
    try { const value = await sdk.call<Overview>(method, { ...args, taskQuery: query.value, taskProject: project.value }); if (!disposed) { accept(value, false, ['run', 'stop'].includes(method)); if (method === 'connect') linking.value = false } }
    catch (cause) { if (!disposed) report(cause) }
    finally { pending.value = false; if (active.value) schedule() }
  }
  watch([project, query], () => { sdk.setState({ project: project.value, query: query.value }) })
  watch([query, project], () => { clearTimeout(searchTimer); searchTimer = setTimeout(() => { void refresh() }, 180) })
  const unsubscribe = sdk.onContextChanged(value => { const visible = !context.value.visible && value.visible; context.value = value; locale.value = value.locale.startsWith('ru') ? 'ru' : 'en'; if (!value.visible) clearTimeout(timer); else if (visible) void refresh() })
  onUnmounted(() => { disposed = true; epoch++; clearTimeout(timer); clearTimeout(searchTimer); unsubscribe() })
  void refresh()
  return { t, data, error, pending, project, query, rootPath, linking, output, active, groups, refresh, action }
}
