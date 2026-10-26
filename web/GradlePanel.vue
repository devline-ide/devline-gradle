<script setup lang="ts">
import { UIButton, UIIcon, UIInput, UIInfoTip } from '@devline/ui-web'
import { useGradle } from './useGradle'
const { t, data, error, pending, project, query, rootPath, linking, output, active, groups, refresh, action } = useGradle()
</script>
<template>
  <section class="gradle">
    <div class="gradle__toolbar">
      <strong>{{ t('projects') }}</strong>
      <UIButton variant="quiet" :disabled="pending || active || !data?.available" :aria-label="t('sync')" :title="t('sync')" @click="action('sync')"><UIIcon name="arrow-clockwise" /></UIButton>
      <UIButton variant="quiet" :disabled="pending" :aria-label="t('connect')" :title="t('connect')" :aria-expanded="linking" @click="linking = !linking"><UIIcon name="folder-plus" /></UIButton>
      <UIButton variant="quiet" :disabled="pending || !active || data?.operation?.status === 'cancelling'" :aria-label="t('stop')" :title="t('stop')" @click="action('stop', { operationId: data?.operation?.id })"><UIIcon name="stop-fill" /></UIButton>
      <UIInfoTip :text="t('help')" :label="t('projects')" />
    </div>
    <form v-if="linking" class="gradle__link" @submit.prevent="action('connect', { rootPath })"><UIInput v-model="rootPath" :aria-label="t('directory')" :placeholder="t('directory')" :help-text="t('directoryHelp')" :disabled="pending || active" @keydown.enter.prevent="action('connect', { rootPath })" /><div><UIButton :disabled="pending || active || !rootPath.trim()" @click="action('connect', { rootPath })">{{ t('connect') }}</UIButton><UIButton variant="quiet" @click="linking = false">{{ t('cancel') }}</UIButton></div></form>
    <div v-if="error" role="alert" class="gradle__error is-selectable">{{ error }} <UIButton variant="quiet" :disabled="pending" @click="refresh()">{{ t('retry') }}</UIButton></div>
    <p v-if="!data && !error" role="status">{{ t('loading') }}</p>
    <template v-if="data">
      <template v-if="data.available">
        <div class="gradle__root"><UIIcon name="folder2-open" /><strong :title="data.rootPath">{{ data.rootName }}</strong><UIButton v-if="data.selectionSource === 'linked'" variant="quiet" :disabled="pending || active" :title="t('disconnect')" :aria-label="t('disconnect')" @click="action('disconnect')"><UIIcon name="x" /></UIButton></div>
        <p class="gradle__hint">{{ data.wrapper ? t('wrapper') : t('fallback', { version: data.fallbackVersion }) }}</p>
        <template v-if="!data.synced && !active"><p>{{ t('syncHint') }}</p><UIButton :disabled="pending" @click="action('sync')">{{ t('sync') }}</UIButton></template>
        <template v-if="data.synced">
          <div class="gradle__project-list" :aria-label="t('projects')"><UIButton variant="quiet" :class="{ 'is-selected': !project }" @click="project = ''">{{ t('allProjects') }}</UIButton><UIButton v-for="item in data.projects" :key="item.path" variant="quiet" :class="{ 'is-selected': project === item.path }" :style="{ paddingInlineStart: ((item.path === ':' ? 0 : item.path.split(':').length - 1) * 12 + 6) + 'px' }" :title="item.path" @click="project = item.path"><UIIcon name="box" />{{ item.path === ':' ? item.name : item.path }}</UIButton></div>
          <div class="gradle__tasks-heading"><strong>{{ t('tasks') }}</strong><span>{{ data.tasksTotal }}</span></div>
          <UIInput v-model="query" type="search" :aria-label="t('filter')" :placeholder="t('filter')" />
          <div class="gradle__tasks"><details v-for="group in groups" :key="group.name" open><summary>{{ group.name }}</summary><div v-for="task in group.tasks" :key="task.path" class="gradle__task"><span class="is-selectable" :title="task.description || task.path">{{ task.path }}</span><UIButton variant="quiet" :disabled="pending || active" :aria-label="t('runTask', { task: task.path })" :title="t('runTask', { task: task.path })" @click="action('run', { tasks: [task.path] })"><UIIcon name="play-fill" /></UIButton></div></details><p v-if="!groups.length">{{ t('noTasks') }}</p></div>
          <UIButton v-if="data.nextTaskOffset < data.tasksTotal || data.nextProjectOffset < data.projectsTotal" variant="quiet" :disabled="pending" @click="refresh(true)">{{ t('more') }}</UIButton>
        </template>
      </template>
      <div v-else><p>{{ t('noBuild') }}</p><UIButton v-if="data.selectionSource === 'linked'" variant="quiet" :disabled="pending || active" @click="action('disconnect')">{{ t('disconnect') }}</UIButton></div>
      <div v-if="data.operation" class="gradle__operation" role="status"><UIIcon :name="active ? 'hourglass-split' : data.operation.status === 'succeeded' ? 'check-circle' : 'exclamation-circle'" /><span>{{ t('state.' + data.operation.status) }}</span></div>
      <p v-if="data.operation?.error" class="gradle__error is-selectable">{{ data.operation.error }}</p>
      <details v-if="output" class="gradle__output" open><summary>{{ t('output') }}</summary><pre tabindex="0" class="is-selectable">{{ output }}</pre></details>
    </template>
  </section>
</template>
