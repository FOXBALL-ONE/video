<template>
  <main class="script-page min-h-[calc(100vh-65px)] bg-[#f5f6fa] px-4 pb-24 pt-7 text-[#20283b] sm:px-6 lg:px-8">
    <div class="mx-auto max-w-[1177px]">
      <header class="flex flex-col gap-6 border-b border-[#e5e8ef] pb-7 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">01 / 07 · EPISODE WORKFLOW</p>
          <h1 class="mt-5 text-[31px] font-medium leading-[1.18]">剧本导入</h1>
          <p class="mt-3 max-w-[620px] text-[13px] leading-6 text-[#8a94aa]">将完整剧本或小说导入工作区，识别场次、角色、对白与制作资产。</p>
        </div>
        <div v-if="activeEpisode" class="rounded-[10px] border border-[#dedaff] bg-[#f4f2ff] px-3 py-2 text-xs text-[#5d52dd]">当前剧集：<strong>{{ activeEpisode.title }}</strong></div>
      </header>

      <ProductionSteps active="script" :show-episode="false" />

      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_330px]">
        <n-card :content-style="{ padding: '0 24px 24px' }" class="!rounded-[17px] !border-[#dfe3eb]">
          <div class="flex min-h-[108px] flex-col items-start justify-between gap-4 border-b border-[#e8eaf0] px-1 py-5 sm:flex-row sm:items-center sm:px-2">
            <div>
              <h2 id="import-title" class="m-0 text-lg font-medium">导入剧本正文</h2>
              <p class="mt-2 text-xs text-[#8994a8]">粘贴全文或上传文本文件，解析后会同步更新当前项目结构。</p>
            </div>
            <label class="inline-flex h-9 cursor-pointer items-center gap-1.5 rounded-[9px] border border-[#d8d2ff] px-3 text-xs font-medium text-[#5d52ec] transition-colors hover:bg-[#f4f2ff]">
              <span aria-hidden="true" class="text-sm">↥</span> 上传文件
              <input ref="fileInput" type="file" accept=".txt,.docx" class="sr-only" @change="onFileChange" />
            </label>
          </div>

          <div
            class="script-dropzone relative mt-5 overflow-hidden rounded-[12px] border border-dashed border-[#d9dce3] bg-[#fbfcfe] transition-colors"
            :class="isDragging ? 'border-[#6654ee] bg-[#f4f2ff]' : ''"
            @dragenter.prevent="isDragging = true"
            @dragover.prevent="isDragging = true"
            @dragleave.prevent="isDragging = false"
            @drop.prevent="onDrop"
          >
            <div class="flex items-center justify-between border-b border-[#e8eaf0] px-4 py-2.5">
              <span class="text-[11px] font-medium text-[#687388]">剧本正文</span>
              <span class="text-[11px] text-[#929bad]">{{ wordCount }} 字</span>
            </div>
            <textarea
              v-model="scriptText"
              class="block min-h-[330px] w-full resize-y bg-transparent px-4 py-3 text-sm leading-7 text-[#30394a] outline-none placeholder:text-[#a1a8b5]"
              placeholder="在此粘贴完整剧本/小说内容...\n\n支持一次性粘贴全部内容（多集），AI 将自动识别章节标记并拆分。"
              aria-label="剧本正文"
              @input="parsed = false"
            ></textarea>
          </div>

          <div class="mt-4 border-b border-[#e8eaf0] pb-5">
            <div v-if="parseError" class="rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">
              <strong class="block font-medium">解析失败：{{ parseError.message }}</strong>
              <div class="mt-1.5 grid grid-cols-1 gap-1 text-[11px] text-[#8e6262] sm:grid-cols-2">
                <span>错误代码：{{ parseError.error_code ?? '未返回' }}</span>
                <span>服务提供商：{{ parseError.provider || '未返回' }}</span>
                <span class="sm:col-span-2">请求 ID：{{ parseError.request_id || '未返回' }}</span>
              </div>
              <pre v-if="parseError.response_content" class="mt-2 max-h-32 overflow-auto whitespace-pre-wrap break-words rounded-md bg-white px-2 py-1.5 text-[10px] leading-4 text-[#765858]">{{ parseError.response_content }}</pre>
            </div>
            <div class="mt-4 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
              <span class="text-xs text-[#929bad]">支持 .txt 文本文件</span>
              <button type="button" class="inline-flex h-10 items-center justify-center gap-2 rounded-[9px] bg-[#6654ee] px-4 text-sm font-medium text-white transition-colors hover:bg-[#5848d6] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!scriptText.trim() || !episodeQuery || parsing" @click="parseScript">
              <span aria-hidden="true" class="text-base">✦</span>{{ parsing ? '解析中...' : '开始解析' }}
              </button>
            </div>
          </div>

          <div class="pt-5" aria-labelledby="generate-title">
            <div class="flex items-center justify-between gap-3">
              <div>
                <h2 id="generate-title" class="m-0 text-sm font-semibold">AI 生成剧本</h2>
                <p class="mt-1 text-xs text-[#929bad]">从一句话创意开始，快速生成可编辑初稿。</p>
              </div>
              <span class="rounded-md bg-[#f0eeff] px-2 py-1 text-[10px] font-medium text-[#5d52ec]">智能创作</span>
            </div>
            <div class="mt-4 flex flex-col gap-3 sm:flex-row">
              <input v-model="generationPrompt" class="block h-10 min-w-0 flex-1 rounded-[9px] border border-[#dfe2e8] bg-white px-3 text-sm text-[#30394a] outline-none transition-shadow placeholder:text-[#a1a8b5] focus:border-[#9b91f5] focus:ring-2 focus:ring-[#eceaff]" placeholder="如：穿越回古代的爱情故事" aria-label="AI 剧本创作描述" @keyup.enter="generateScript" />
              <button type="button" class="inline-flex h-10 shrink-0 items-center justify-center gap-2 rounded-[9px] border border-[#d8d2ff] px-4 text-sm font-medium text-[#5d52ec] transition-colors hover:bg-[#f4f2ff] disabled:cursor-not-allowed disabled:opacity-50" :disabled="!generationPrompt.trim() || generating" @click="generateScript"><span aria-hidden="true">✦</span>{{ generating ? '生成中...' : 'AI 写剧本' }}</button>
            </div>
            <div class="mt-3 flex flex-wrap items-center gap-2">
              <span class="mr-1 text-xs text-[#929bad]">风格</span>
              <button v-for="style in styles" :key="style" type="button" class="rounded-full border px-3 py-1 text-[11px] transition-colors" :class="selectedStyle === style ? 'border-[#776ce9] bg-[#f0eeff] text-[#5c50dc]' : 'border-[#dfe2e8] text-[#646d7e] hover:bg-[#f7f7fa]'" :aria-pressed="selectedStyle === style" @click="selectedStyle = selectedStyle === style ? '' : style">{{ style }}</button>
            </div>
            <p v-if="generatedNotice" class="mt-3 text-xs text-[#687388]" role="status">{{ generatedNotice }}</p>
          </div>

          <section v-if="parsed" class="mt-5 rounded-[11px] border border-[#dfe3eb] bg-[#fbfbfd] p-4" aria-labelledby="parsed-title">
            <div class="flex flex-wrap items-center justify-between gap-2">
              <div><h2 id="parsed-title" class="m-0 text-sm font-semibold">解析预览</h2><p class="mt-1 text-xs text-[#929bad]">结构化结果已就绪，可继续维护资产。</p></div>
              <span class="rounded-md bg-[#e9f7f1] px-2 py-1 text-[10px] font-medium text-[#278d68]">已解析</span>
            </div>
            <div class="mt-4 grid grid-cols-2 gap-2 sm:grid-cols-4">
              <div v-for="stat in parsedStats" :key="stat.label" class="rounded-[9px] bg-white px-3 py-2.5"><strong class="block text-lg font-semibold text-[#30394a]">{{ stat.value }}</strong><span class="mt-0.5 block text-[11px] text-[#8b93a2]">{{ stat.label }}</span></div>
            </div>
            <div v-if="analysisResult" class="mt-4 grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">人物资产</span>
                <div v-if="analysisResult.characters?.length" class="mt-2 flex flex-wrap gap-1.5">
                  <span v-for="item in analysisResult.characters" :key="item.id || item.name" class="rounded-md bg-[#eceaff] px-2 py-1 text-[11px] text-[#5d52dd]">{{ item.name }}</span>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到人物</p>
              </div>
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">场景资产</span>
                <div v-if="analysisResult.scenes?.length" class="mt-2 space-y-1.5">
                  <p v-for="item in analysisResult.scenes.slice(0, 4)" :key="item.id || item.name" class="m-0 truncate text-xs text-[#4f596d]">{{ item.name || item.location || '未命名场景' }}</p>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到场景</p>
              </div>
              <div class="rounded-[9px] bg-white p-3">
                <span class="text-[11px] text-[#929bad]">道具资产</span>
                <div v-if="analysisResult.props?.length" class="mt-2 space-y-1.5">
                  <p v-for="item in analysisResult.props.slice(0, 4)" :key="item.id || item.name" class="m-0 truncate text-xs text-[#4f596d]">{{ item.name }}</p>
                </div>
                <p v-else class="mt-2 text-xs text-[#a1a8b5]">未识别到道具</p>
              </div>
            </div>
            <p v-if="analysisResult?.overall_summary" class="mt-3 text-xs leading-5 text-[#687388]">{{ analysisResult.overall_summary }}</p>
          </section>
        </n-card>

        <aside class="script-assistant rounded-[17px] border border-[#dfe3eb] bg-white">
          <div class="border-b border-[#e8eaf0] px-5 py-[18px]"><div class="flex items-center gap-2"><span class="text-xl text-[#5d51ef]" aria-hidden="true">✦</span><div><h2 class="m-0 text-[13px] font-semibold">剧本导入助手</h2><p class="mt-1.5 text-[11px] text-[#8e98aa]">在线 · 已读取当前节点</p></div></div></div>
          <div class="space-y-4 px-5 py-5">
            <div class="rounded-[11px] bg-[#f7f7fb] px-3 py-3"><span class="text-[11px] text-[#929bad]">当前上下文</span><strong class="mt-2 block text-[13px] font-semibold text-[#273148]">{{ activeEpisode?.title || '项目剧本' }}</strong><div class="mt-2 flex flex-wrap gap-1.5"><span class="rounded-md bg-[#eceaff] px-2 py-1 text-[10px] text-[#6157e9]">正文结构</span><span class="rounded-md bg-[#eceaff] px-2 py-1 text-[10px] text-[#6157e9]">历史版本</span></div></div>
            <div class="flex gap-2.5 rounded-[11px] bg-[#f5f6f8] px-3 py-3"><span class="shrink-0 text-[#5e52ee]" aria-hidden="true">✦</span><p class="m-0 text-xs leading-[1.65] text-[#687388]">先导入剧本正文，再运行解析。解析完成后可以在资产管理中检查角色、场景和道具。</p></div>
            <div class="border-t border-[#e8eaf0] pt-4"><span class="text-[11px] text-[#8994a8]">解析状态</span><div class="mt-3 flex items-center justify-between text-xs"><span class="text-[#687388]">当前版本</span><strong :class="parsed ? 'text-[#278d68]' : 'text-[#d08a2d]'">{{ parsed ? '已解析' : '待解析' }}</strong></div><div class="mt-3 h-1.5 overflow-hidden rounded-full bg-[#ececf4]"><div class="h-full rounded-full bg-[#6654ee] transition-all" :style="{ width: parsed ? '100%' : scriptText.trim() ? '25%' : '0%' }"></div></div></div>
          </div>
        </aside>
      </section>
    </div>

    <n-modal
      v-model:show="analysisSummaryVisible"
      preset="card"
      title="采纳解析结果"
      class="w-[min(94vw,760px)]"
      :mask-closable="false"
    >
      <div class="flex flex-wrap items-center justify-between gap-3">
        <p class="m-0 text-xs leading-5 text-[#7e899e]">本次结果已暂存。采纳后会创建尚不存在的剧集资产。</p>
        <div class="flex items-center gap-2">
          <n-button text size="tiny" @click="analysisDetailsVisible = !analysisDetailsVisible">{{ analysisDetailsVisible ? '收起详情' : '查看详情' }}</n-button>
          <span v-if="analysisAdoptionStatus === 'ADOPTED'" class="rounded-md bg-[#e9f7f1] px-2 py-1 text-[10px] font-medium text-[#278d68]">已采纳</span>
        </div>
      </div>
      <n-checkbox-group v-if="adoptableTypeResults.length" v-model:value="analysisAdoptionTypes" class="mt-4 flex flex-wrap gap-x-5 gap-y-2">
        <n-checkbox v-for="typeResult in adoptableTypeResults" :key="typeResult.type" :value="typeResult.type">
          {{ assetTypeLabel(typeResult.type) }} · {{ typeResult.count }}
        </n-checkbox>
      </n-checkbox-group>
      <div v-if="failedTypeResults.length" class="mt-4 space-y-2">
        <div v-for="typeResult in failedTypeResults" :key="typeResult.type" class="rounded-[8px] border border-[#f1d3d3] bg-[#fff8f8] px-3 py-2 text-xs text-[#985454]">
          {{ assetTypeLabel(typeResult.type) }}未完成：{{ typeResult.error?.message || '模型解析失败' }}
        </div>
      </div>
      <div class="mt-4 grid max-h-[58vh] grid-cols-1 gap-3 overflow-y-auto pr-1 md:grid-cols-3">
        <section class="rounded-[10px] border border-[#e5e3fb] bg-[#faf9ff] p-3" aria-labelledby="summary-characters-title">
          <div class="flex items-center justify-between gap-2">
            <h3 id="summary-characters-title" class="m-0 text-xs font-semibold text-[#4f46b8]">角色简介</h3>
            <span class="text-[11px] text-[#8f88d5]">{{ characterSummaries.length }} 位</span>
          </div>
          <div v-if="characterSummaries.length" class="mt-3 space-y-2.5">
            <article v-for="item in (analysisDetailsVisible ? characterSummaries : characterSummaries.slice(0, 4))" :key="item.key" class="rounded-[8px] border border-[#ecebfa] bg-white px-2.5 py-2">
              <strong class="block truncate text-xs font-medium text-[#30394a]">{{ item.name }}</strong>
              <p class="mt-1.5 whitespace-pre-wrap break-words text-[11px] leading-5 text-[#687388]">{{ item.description }}</p>
            </article>
          </div>
          <p v-else class="mt-3 text-[11px] text-[#a1a8b5]">未识别到角色简介</p>
        </section>

        <section class="rounded-[10px] border border-[#dcecf0] bg-[#f8fcfd] p-3" aria-labelledby="summary-scenes-title">
          <div class="flex items-center justify-between gap-2">
            <h3 id="summary-scenes-title" class="m-0 text-xs font-semibold text-[#287788]">场景简介</h3>
            <span class="text-[11px] text-[#6e9eaa]">{{ sceneSummaries.length }} 个</span>
          </div>
          <div v-if="sceneSummaries.length" class="mt-3 space-y-2.5">
            <article v-for="item in (analysisDetailsVisible ? sceneSummaries : sceneSummaries.slice(0, 4))" :key="item.key" class="rounded-[8px] border border-[#e4f0f2] bg-white px-2.5 py-2">
              <strong class="block truncate text-xs font-medium text-[#30394a]">{{ item.name }}</strong>
              <p class="mt-1.5 whitespace-pre-wrap break-words text-[11px] leading-5 text-[#687388]">{{ item.description }}</p>
            </article>
          </div>
          <p v-else class="mt-3 text-[11px] text-[#a1a8b5]">未识别到场景简介</p>
        </section>

        <section class="rounded-[10px] border border-[#f1e6d6] bg-[#fffbf5] p-3" aria-labelledby="summary-props-title">
          <div class="flex items-center justify-between gap-2">
            <h3 id="summary-props-title" class="m-0 text-xs font-semibold text-[#a46b23]">道具简介</h3>
            <span class="text-[11px] text-[#b28a59]">{{ propSummaries.length }} 件</span>
          </div>
          <div v-if="propSummaries.length" class="mt-3 space-y-2.5">
            <article v-for="item in (analysisDetailsVisible ? propSummaries : propSummaries.slice(0, 4))" :key="item.key" class="rounded-[8px] border border-[#f3eadc] bg-white px-2.5 py-2">
              <strong class="block truncate text-xs font-medium text-[#30394a]">{{ item.name }}</strong>
              <p class="mt-1.5 whitespace-pre-wrap break-words text-[11px] leading-5 text-[#687388]">{{ item.description }}</p>
            </article>
          </div>
          <p v-else class="mt-3 text-[11px] text-[#a1a8b5]">未识别到道具简介</p>
        </section>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button :loading="analysisDiscarding" :disabled="analysisAdopting" @click="discardAnalysisResult">暂不采纳</n-button>
          <n-button type="primary" color="#6654ee" :loading="analysisAdopting" :disabled="!analysisAdoptionTypes.length || analysisAdoptionStatus === 'ADOPTED' || analysisDiscarding" @click="adoptAnalysisResult">采纳并写入资产库</n-button>
        </div>
      </template>
    </n-modal>

    <n-modal v-model:show="analysisModalVisible" preset="card" title="提取剧本资产" class="w-[min(94vw,620px)]" :mask-closable="false">
      <p class="-mt-2 text-xs text-[#8a94aa]">选择要提取的资产类型和对应模型，任务提交后将在后台运行。</p>
      <div class="mt-4 flex items-center justify-between rounded-[9px] border border-[#dfe3eb] bg-white px-3.5 py-3">
        <div>
          <p class="m-0 text-xs font-medium text-[#30394a]">流式传输</p>
          <p class="mt-1 text-[11px] text-[#8994a8]">默认启用，可按本次解析需要关闭。</p>
        </div>
        <n-switch v-model:value="analysisStream" />
      </div>
      <div class="mt-4 space-y-3">
        <div v-for="job in analysisJobs" :key="job.type" class="rounded-[12px] border border-[#dfe3eb] bg-[#fbfcfe] p-3.5">
          <div class="flex items-start gap-3">
            <n-checkbox v-model:checked="job.enabled" class="mt-1" />
            <div class="min-w-0 flex-1">
              <strong class="block text-sm text-[#20283b]">{{ job.label }}</strong>
              <p class="mt-1 text-[11px] leading-5 text-[#7e899e]">{{ job.description }}</p>
              <div class="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2">
                <n-select v-model:value="job.providerId" :options="providerOptions" :disabled="!providerOptions.length" size="small" placeholder="选择模型提供商" @update:value="onAnalysisProviderChange(job, $event)" />
                <n-select v-model:value="job.model" :options="modelOptionsFor(job)" :disabled="!job.providerId" filterable size="small" placeholder="选择模型" />
                <n-select v-model:value="job.promptVersionId" :options="job.promptOptions" :loading="job.promptLoading" :disabled="job.promptLoading || !job.promptOptions.length" size="small" placeholder="模板版本（默认）" />
                <n-button text size="tiny" type="primary" class="justify-self-start" @click="navigateTo('/templates')">管理模板</n-button>
              </div>
              <p v-if="job.promptError" class="mt-2 mb-0 flex items-center gap-2 text-[11px] text-[#a34d4d]">提示词选项加载失败。<button type="button" class="text-[#6654ee] underline" @click="loadAnalysisPromptOptionsFor(job)">重试</button></p>
            </div>
          </div>
        </div>
      </div>
      <div v-if="analysisTaskStatus" class="mt-4 rounded-[9px] border border-[#dcecf0] bg-[#f7fcfd] px-3 py-2 text-xs text-[#287788]" role="status">
        <div class="flex items-center justify-between gap-3">
          <span>{{ analysisTaskStatus === 'QUEUED' ? '任务排队中' : analysisTaskStatus === 'RUNNING' ? '模型正在解析剧本' : analysisTaskStatus === 'CANCEL_REQUESTED' ? '正在取消任务' : analysisTaskStatus === 'SUCCEEDED' ? '解析结果已暂存' : analysisTaskStatus === 'CANCELLED' ? '任务已取消' : '解析失败' }}</span>
          <strong v-if="analysisTaskStatus === 'QUEUED' || analysisTaskStatus === 'RUNNING'" class="shrink-0 font-medium tabular-nums">{{ analysisTaskProgress }}%</strong>
        </div>
        <div v-if="analysisTaskStatus === 'QUEUED' || analysisTaskStatus === 'RUNNING'" class="mt-2 h-1.5 overflow-hidden rounded-full bg-[#dfeef1]">
          <div class="h-full rounded-full bg-[#4aa0af] transition-[width] duration-300" :style="{ width: `${analysisTaskProgress}%` }"></div>
        </div>
        <p v-if="analysisTaskStage && analysisTaskStatus !== 'SUCCEEDED'" class="mt-1.5 mb-0 truncate text-[11px] text-[#62919b]">{{ analysisTaskStage }}</p>
      </div>
      <div v-if="analysisTaskError" class="mt-3 rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]" role="alert">{{ analysisTaskError }}</div>
      <div v-if="analysisTypeResults.length" class="mt-3 grid grid-cols-1 gap-2 sm:grid-cols-2" aria-label="资产提取子任务状态">
        <div v-for="typeResult in analysisTypeResults" :key="typeResult.type" class="rounded-[8px] border border-[#e5e8ef] bg-white px-3 py-2 text-xs">
          <div class="flex items-center justify-between gap-2"><span class="font-medium text-[#4f596d]">{{ assetTypeLabel(typeResult.type) }}</span><span class="text-[#8994a8]">{{ typeResult.status === 'SUCCEEDED' ? '已完成' : typeResult.status === 'FAILED' ? '失败' : typeResult.status === 'CANCELLED' ? '已取消' : '解析中' }}</span></div>
          <div v-if="typeResult.prompt_key" class="mt-1 truncate text-[10px] text-[#9aa3b4]">{{ typeResult.prompt_key }} · v{{ typeResult.prompt_version_id || '-' }}</div>
          <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-[#ececf4]"><div class="h-full rounded-full bg-[#6654ee] transition-[width]" :style="{ width: `${typeResult.progress ?? (typeResult.status === 'SUCCEEDED' || typeResult.status === 'FAILED' || typeResult.status === 'CANCELLED' ? 100 : 10)}%` }"></div></div>
        </div>
      </div>
      <template #footer>
        <div class="flex justify-end gap-2">
          <n-button v-if="analysisTaskId && analysisTaskStatus === 'FAILED'" type="warning" secondary :loading="analysisRetrying" :disabled="analysisSubmitting || analysisAdopting || analysisDiscarding" @click="retryAnalysisTask">发起重试</n-button>
          <n-button v-if="analysisTaskId && ['QUEUED', 'RUNNING', 'CANCEL_REQUESTED'].includes(analysisTaskStatus)" type="warning" secondary :disabled="analysisSubmitting || analysisAdopting || analysisTaskStatus === 'CANCEL_REQUESTED'" @click="cancelAnalysisTask">取消任务</n-button>
          <n-button :disabled="analysisSubmitting" @click="analysisModalVisible = false">取消</n-button>
          <n-button type="primary" color="#6654ee" :loading="analysisSubmitting" :disabled="!selectedAnalysisCount || !analysisJobsValid" @click="startAnalysisTask">开始提取</n-button>
        </div>
      </template>
    </n-modal>

    <footer class="script-footer fixed bottom-0 left-0 right-0 z-10 flex h-14 items-center justify-between border-t border-[#e1e3e8] bg-white px-5 sm:px-6">
      <button
        type="button"
        class="inline-flex h-10 items-center gap-1.5 rounded-md px-3 text-sm text-[#737c8d] transition-colors hover:bg-[#f5f6f8] disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="!hasProject"
        @click="goPrevious"
      >
        <span aria-hidden="true">‹</span> 上一步
      </button>
      <button
        type="button"
        class="inline-flex h-10 items-center gap-1.5 rounded-md bg-[#f28a21] px-4 text-sm font-medium text-white transition-colors hover:bg-[#df7914] disabled:cursor-not-allowed disabled:opacity-50"
        :disabled="!parsed"
        @click="goNext"
      >
        下一步 <span aria-hidden="true">›</span>
      </button>
    </footer>
  </main>
</template>

<script lang="ts" setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'

const message = useMessage()
const http = useHttp()
const { activeProject, activeProjectId, loadProjects } = useProjectWorkspace()
const { activeEpisodeId, activeEpisode, loadEpisodes, loadLibrary } = useEpisodeWorkspace()
const route = useRoute()

interface ScriptVersion {
  id: number
  version_no: number
  version_name: string
  status: string
}

interface ScriptStats {
  scene_count: number
  character_count: number
  dialogue_count: number
  prop_count?: number
  estimated_duration_seconds: number
  structure_score: number | null
}

interface ScriptResponse {
  document_id: number
  title: string
  version: ScriptVersion
  versions: ScriptVersion[]
  content: string
  scenes: unknown[]
  characters: unknown[]
  dialogues: unknown[]
  quality_checks: unknown[]
  stats: ScriptStats
  analysis?: { status: string; provider_config_id: number | null; model: string | null; analyzed_at: string | null; result: ScriptAnalysis | null }
}

interface ScriptAnalysisItem {
  id?: string
  name?: string
  location?: string
  description?: string
  identity?: string
  appearance?: string
  personality?: string[]
  motivation?: string
  relationship?: string
  role_type?: string
  environment_type?: string
  time?: string
  weather?: string
  lighting?: string
  visual_style?: string
  plot_purpose?: string
  actions?: string[]
  category?: string
  owner?: string
  function?: string
  continuity_notes?: string
  remarks?: string
}
interface ScriptAnalysis { title?: string; genre?: string; overall_summary?: string; characters?: ScriptAnalysisItem[]; scenes?: ScriptAnalysisItem[]; props?: ScriptAnalysisItem[]; costumes?: ScriptAnalysisItem[] }
interface AnalysisSummaryItem { key: string; name: string; description: string }
interface AnalysisTaskError { message: string; error_code?: string | null; provider?: string | null; request_id?: string | null }
interface AnalysisTypeResult { type: AnalysisJob['type']; status: string; provider_config_id: number | null; model: string | null; provider: string | null; request_id: string | null; count: number; result_hash?: string | null; prompt_key?: string | null; prompt_category?: string | null; prompt_version_id?: number | null; template_hash?: string | null; selection_source?: string | null; progress?: number; stage?: string | null; started_at?: string | null; completed_at?: string | null; latency_ms?: number | null; error?: AnalysisTaskError | null }
interface AnalysisTaskResponse {
  task_id: string
  status: string
  progress: number
  stage: string | null
  result_status: string
  asset_types: AnalysisJob['type'][]
  type_results: AnalysisTypeResult[]
  version_id: number
  content_hash: string
  adopted_types: AnalysisJob['type'][]
  result: ScriptAnalysis | null
  prompt_selections?: Array<Record<string, unknown>>
  stats: { character_count: number; scene_count: number; prop_count: number }
  error?: AnalysisTaskError | null
}
interface AnalysisAdoptionResponse {
  task_id: string
  task_status: string
  adoption_status: string
  version_id: number
  selected_asset_types: AnalysisJob['type'][]
  created_count: number
  skipped_count: number
  conflict_count: number
  missing_name_count: number
  asset_ids: number[]
}

interface ProviderConfig {
  id: number
  provider: string
  model_id: string
  base_url: string
  enabled: boolean
}

interface ParseError {
  message: string
  error_code?: number | null
  response_content?: string | null
  provider?: string
  request_id?: string
}

interface AnalysisJob {
  type: 'SCENE_BACKGROUND' | 'CHARACTER' | 'PROP'
  label: string
  description: string
  enabled: boolean
  providerId: number | null
  model: string
  promptVersionId: number | null
  promptOptions: Array<{ label: string; value: number; prompt_key?: string; category?: string; content_hash?: string; fallback?: boolean }>
  promptLoading: boolean
  promptError: boolean
}

const styles = ['古风', '悬疑', '甜宠', '科幻', '都市', '奇幻']

const scriptText = ref('测试剧本')
const generationPrompt = ref('')
const selectedStyle = ref('')
const generatedNotice = ref('')
const parsed = ref(false)
const parsing = ref(false)
const generating = ref(false)
const isDragging = ref(false)
const analysisSummaryVisible = ref(false)
const providerConfigs = ref<ProviderConfig[]>([])
const providerModelOptions = ref<Record<number, { label: string; value: string }[]>>({})
const scriptStats = ref<ScriptStats>({
  scene_count: 0,
  character_count: 0,
  dialogue_count: 0,
  estimated_duration_seconds: 0,
  structure_score: null,
})
const analysisResult = ref<ScriptAnalysis | null>(null)
const parseError = ref<ParseError | null>(null)
const analysisModalVisible = ref(false)
const analysisSubmitting = ref(false)
const analysisTaskStatus = ref('')
const analysisTaskError = ref('')
const analysisTaskId = ref('')
const analysisTaskProgress = ref(0)
const analysisTaskStage = ref('')
const analysisTypeResults = ref<AnalysisTypeResult[]>([])
const analysisAdoptionTypes = ref<AnalysisJob['type'][]>([])
const analysisAdoptedTypeNames = ref<AnalysisJob['type'][]>([])
const analysisAdoptionStatus = ref('')
const analysisDetailsVisible = ref(false)
const analysisAdopting = ref(false)
const analysisDiscarding = ref(false)
const analysisRetrying = ref(false)
const analysisSubmissionKey = ref('')
const analysisSubmissionFingerprint = ref('')
const analysisStream = ref(true)
const analysisAdoptionKey = ref('')
const analysisAdoptionFingerprint = ref('')
const currentVersionId = ref<number | null>(null)
const analysisContentHash = ref('')
let analysisPollToken = 0
let analysisPollTimer: number | null = null
const analysisJobs = ref<AnalysisJob[]>([
  { type: 'SCENE_BACKGROUND', label: '场景提取', description: '提取地点、时间、天气、光线、氛围和视觉风格。', enabled: true, providerId: null, model: '', promptVersionId: null, promptOptions: [], promptLoading: false, promptError: false },
  { type: 'CHARACTER', label: '角色提取', description: '提取身份、外貌、性格、关系和出场要求。', enabled: true, providerId: null, model: '', promptVersionId: null, promptOptions: [], promptLoading: false, promptError: false },
  { type: 'PROP', label: '道具提取', description: '提取关键道具、持有人、剧情功能和连续性要求。', enabled: true, providerId: null, model: '', promptVersionId: null, promptOptions: [], promptLoading: false, promptError: false },
])

const fileInput = ref<HTMLInputElement | null>(null)
const hasProject = computed(() => Boolean(activeProject.value?.id || activeProjectId.value))
const projectId = computed(() => String(route.params.projectId || activeProject.value?.id || activeProjectId.value || ''))
const episodeId = computed(() => {
  const value = String(route.params.episodeId || activeEpisodeId.value || '')
  return /^\d+$/.test(projectId.value) ? (/^\d+$/.test(value) ? value : '') : value
})
const episodeQuery = computed(() => {
  const id = Number(episodeId.value)
  return Number.isInteger(id) && id > 0 ? { episode_id: id } : undefined
})
const wordCount = computed(() => scriptText.value.length)
const providerOptions = computed(() => providerConfigs.value.map((config) => ({ label: config.provider, value: config.id })))
const selectedAnalysisCount = computed(() => analysisJobs.value.filter((job) => job.enabled).length)
const analysisJobsValid = computed(() => analysisJobs.value.filter((job) => job.enabled).every((job) => job.providerId && job.model && !job.promptLoading && !job.promptError && job.promptVersionId && job.promptOptions.some((option) => option.value === job.promptVersionId)))
const analysisAdoptedTypes = computed(() => new Set(analysisAdoptedTypeNames.value))
const adoptableTypeResults = computed(() => analysisTypeResults.value.filter((item) => item.status === 'SUCCEEDED' && item.count > 0 && !analysisAdoptedTypes.value.has(item.type) && !['DISCARDED', 'SUPERSEDED'].includes(analysisAdoptionStatus.value)))
const failedTypeResults = computed(() => analysisTypeResults.value.filter((item) => item.status === 'FAILED'))
const parsedStats = computed(() => [
  { label: '场景', value: scriptStats.value.scene_count || estimateScenes(scriptText.value) },
  { label: '角色', value: scriptStats.value.character_count || estimateCharacters(scriptText.value) },
  { label: '对白', value: scriptStats.value.dialogue_count || estimateDialogues(scriptText.value) },
  { label: '预计时长', value: formatDuration(scriptStats.value.estimated_duration_seconds || estimateDuration(scriptText.value)) },
])
const characterSummaries = computed<AnalysisSummaryItem[]>(() => (analysisResult.value?.characters || []).map((item, index) => ({
  key: item.id || `character-${index}`,
  name: item.name || `未命名角色${index + 1}`,
  description: toSummaryText([item.role_type, item.identity, item.appearance, item.personality, item.motivation, item.relationship, item.remarks], '暂无角色简介'),
})))
const sceneSummaries = computed<AnalysisSummaryItem[]>(() => (analysisResult.value?.scenes || []).map((item, index) => ({
  key: item.id || `scene-${index}`,
  name: item.name || item.location || `未命名场景${index + 1}`,
  description: toSummaryText([item.location, item.environment_type, item.time, item.weather, item.lighting, item.visual_style, item.plot_purpose, item.actions, item.remarks], '暂无场景简介'),
})))
const propSummaries = computed<AnalysisSummaryItem[]>(() => (analysisResult.value?.props || []).map((item, index) => ({
  key: item.id || `prop-${index}`,
  name: item.name || `未命名道具${index + 1}`,
  description: toSummaryText([item.category, item.description, item.owner, item.function, item.continuity_notes, item.remarks], '暂无道具简介'),
})))

function toSummaryText(parts: unknown[], fallback: string) {
  const values = parts.flatMap((part) => Array.isArray(part) ? part : [part]).map((part) => typeof part === 'string' ? part.trim() : '').filter(Boolean)
  return values.length ? values.join('；') : fallback
}

function estimateScenes(text: string) {
  const matches = text.match(/(?:第\s*\d+\s*场|场景|INT\.|EXT\.)/gi)
  return Math.max(matches?.length || 0, text.trim() ? 1 : 0)
}

function estimateCharacters(text: string) {
  const matches = text.match(/(?:^|\n)\s*[^：:\n]{1,12}[：:]/g)
  return new Set((matches || []).map((item) => item.replace(/[\n：:]/g, '').trim())).size
}

function estimateDialogues(text: string) {
  return (text.match(/(?:^|\n)\s*[^：:\n]{1,12}[：:]/g) || []).length
}

function estimateDuration(text: string) {
  return Math.max(1, Math.ceil(text.replace(/\s/g, '').length / 4))
}

function formatDuration(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function applyResponse(data: ScriptResponse) {
  scriptText.value = data.content || ''
  scriptStats.value = data.stats || scriptStats.value
  currentVersionId.value = data.version?.id || null
  analysisResult.value = data.analysis?.result || null
  parsed.value = data.analysis?.status === 'COMPLETED' || Boolean(data.scenes?.length || data.characters?.length || data.dialogues?.length)
}

async function loadScript() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) return
  try {
    applyResponse(await http.get<ScriptResponse>(`/projects/${id}/script`, episodeQuery.value))
  } catch (error) {
    if (Number((error as { statusCode?: number })?.statusCode) !== 404) {
      message.error(error instanceof Error ? error.message : '剧本加载失败')
    }
  }
}

async function loadProviderConfigs() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providerConfigs.value = response.list.filter((config) => config.enabled)
    providerModelOptions.value = {}
    await Promise.all(providerConfigs.value.map(async (config) => {
      const defaults = config.model_id ? [{ label: config.model_id, value: config.model_id }] : []
      providerModelOptions.value[config.id] = defaults
      try {
        const formData = new FormData()
        formData.append('base_url', config.base_url)
        formData.append('config_id', String(config.id))
        const models = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
        providerModelOptions.value[config.id] = models.list.map((model) => ({ label: model.id, value: model.id }))
      } catch {
        // The saved default model remains available when discovery is unavailable.
      }
    }))
    analysisJobs.value.forEach((job) => {
      if (!job.providerId && providerConfigs.value.length) job.providerId = providerConfigs.value[0].id
      if (!job.model && job.providerId) job.model = providerConfigs.value.find((config) => config.id === job.providerId)?.model_id || ''
    })
  } catch {
    providerConfigs.value = []
  }
}

async function loadAnalysisPromptOptions() {
  const project = Number(projectId.value)
  if (!Number.isInteger(project) || project <= 0) {
    analysisJobs.value.forEach((job) => {
      job.promptOptions = []
      job.promptVersionId = null
      job.promptLoading = false
      job.promptError = false
    })
    return
  }
  await Promise.all(analysisJobs.value.map(async (job) => {
    job.promptLoading = true
    job.promptError = false
    try {
      const response = await http.get<{
        default_version_id?: number | null
        list: Array<{ template_key: string; display_name: string; version_id: number; version_no: number; scope_type: string; fallback?: boolean; fallback_reason?: string | null }>
      }>('/ai/templates/options', {
        project_id: project,
        asset_type: job.type,
        locale: 'zh-CN',
      })
      job.promptOptions = (response.list || []).map((option) => ({
        label: `${option.display_name} · v${option.version_no}${option.fallback ? ' · 通用兼容' : ''}`,
        value: option.version_id,
        prompt_key: option.template_key,
        fallback: option.fallback,
      }))
      job.promptVersionId = response.default_version_id || job.promptOptions[0]?.value || null
    } catch {
      job.promptOptions = []
      job.promptVersionId = null
      job.promptError = true
    } finally {
      job.promptLoading = false
    }
  }))
}

async function loadAnalysisPromptOptionsFor(job: AnalysisJob) {
  const project = Number(projectId.value)
  if (!Number.isInteger(project) || project <= 0) return
  job.promptLoading = true
  job.promptError = false
  try {
    const response = await http.get<{
      default_version_id?: number | null
      list: Array<{ template_key: string; display_name: string; version_id: number; version_no: number; scope_type: string; fallback?: boolean; fallback_reason?: string | null }>
    }>('/ai/templates/options', { project_id: project, asset_type: job.type, locale: 'zh-CN' })
    job.promptOptions = (response.list || []).map((option) => ({ label: `${option.display_name} · v${option.version_no}${option.fallback ? ' · 通用兼容' : ''}`, value: option.version_id, prompt_key: option.template_key, fallback: option.fallback }))
    job.promptVersionId = response.default_version_id || job.promptOptions[0]?.value || null
  } catch {
    job.promptOptions = []
    job.promptVersionId = null
    job.promptError = true
  } finally {
    job.promptLoading = false
  }
}

function modelOptionsFor(job: AnalysisJob) {
  const configured = providerConfigs.value.find((config) => config.id === job.providerId)
  const options = (job.providerId && providerModelOptions.value[job.providerId]) || []
  if (options.length) return options
  return configured?.model_id ? [{ label: configured.model_id, value: configured.model_id }] : []
}

function onAnalysisProviderChange(job: AnalysisJob, providerId: number | null) {
  job.providerId = providerId
  job.model = providerConfigs.value.find((config) => config.id === providerId)?.model_id || ''
}

function openAnalysisModal() {
  analysisTaskError.value = ''
  analysisTaskStatus.value = ''
  analysisTaskProgress.value = 0
  analysisTaskStage.value = ''
  analysisTypeResults.value = []
  analysisAdoptionStatus.value = ''
  analysisSubmissionKey.value = ''
  analysisSubmissionFingerprint.value = ''
  analysisJobs.value.forEach((job) => {
    if (!job.providerId) job.providerId = providerConfigs.value[0]?.id || null
    if (!job.model && job.providerId) job.model = providerConfigs.value.find((config) => config.id === job.providerId)?.model_id || ''
  })
  analysisModalVisible.value = true
}

async function saveScript() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0) throw new Error('当前项目未保存到服务器，请先创建项目')
  const data = await http.put<{ content: string; stats: ScriptStats; version: ScriptVersion }>(`/projects/${id}/script`, scriptText.value, {
    payloadMode: 'json',
    params: episodeQuery.value,
    headers: { 'Content-Type': 'text/plain; charset=UTF-8' },
  })
  scriptStats.value = data.stats || scriptStats.value
  scriptText.value = data.content || scriptText.value
  currentVersionId.value = data.version?.id || null
}

async function parseScript() {
  if (!scriptText.value.trim()) {
    message.warning('请先粘贴或上传剧本内容')
    return
  }
  if (!episodeQuery.value?.episode_id) {
    message.warning('请先选择要解析的剧集')
    return
  }
  parsing.value = true
  parseError.value = null
  generatedNotice.value = ''
  analysisSummaryVisible.value = false
  analysisSubmissionKey.value = ''
  analysisSubmissionFingerprint.value = ''
  try {
    await saveScript()
  } catch (error) {
    parsing.value = false
    parseError.value = extractParseError(error, '剧本保存失败，无法开始解析')
    message.error(parseError.value.message)
    return
  }
  parsing.value = false
  openAnalysisModal()
  message.success('剧本已保存，请选择资产提取范围和模型')
}

async function startAnalysisTask() {
  const jobs = analysisJobs.value.filter((job) => job.enabled)
  if (!jobs.length || !analysisJobsValid.value || !episodeQuery.value?.episode_id || !currentVersionId.value) {
    message.warning('请至少选择一种资产，并为每种资产选择提示词、提供商和模型')
    return
  }
  analysisSubmitting.value = true
  analysisTaskError.value = ''
  try {
    const id = Number(projectId.value)
    const payload = new FormData()
    payload.append('episode_id', String(episodeQuery.value.episode_id))
    payload.append('version_id', String(currentVersionId.value))
    const submittedContentHash = await contentHash(scriptText.value)
    payload.append('content_hash', submittedContentHash)
    payload.append('stream', String(analysisStream.value))
    const submissionFingerprint = `${analysisStream.value ? 'stream' : 'batch'}|${jobs.map((job) => `${job.type}:${job.providerId}:${job.model}:${job.promptVersionId || ''}`).sort().join(',')}`
    if (analysisSubmissionFingerprint.value !== submissionFingerprint) {
      analysisSubmissionFingerprint.value = submissionFingerprint
      analysisSubmissionKey.value = requestKey()
    }
    jobs.forEach((job) => {
      if (job.type === 'CHARACTER') {
        payload.append('character_provider_config_id', String(job.providerId))
        payload.append('character_model', job.model)
        if (job.promptVersionId) payload.append('character_prompt_version_id', String(job.promptVersionId))
      }
      if (job.type === 'SCENE_BACKGROUND') {
        payload.append('scene_background_provider_config_id', String(job.providerId))
        payload.append('scene_background_model', job.model)
        if (job.promptVersionId) payload.append('scene_background_prompt_version_id', String(job.promptVersionId))
      }
      if (job.type === 'PROP') {
        payload.append('prop_provider_config_id', String(job.providerId))
        payload.append('prop_model', job.model)
        if (job.promptVersionId) payload.append('prop_prompt_version_id', String(job.promptVersionId))
      }
    })
    const response = await http.post<{ task_id: string; status: string }, FormData>(`/projects/${id}/script/analysis-tasks`, payload, {
      headers: { 'Idempotency-Key': analysisSubmissionKey.value },
    })
    analysisSubmissionKey.value = ''
    analysisSubmissionFingerprint.value = ''
    analysisPollToken += 1
    if (analysisPollTimer !== null) window.clearTimeout(analysisPollTimer)
    analysisPollTimer = null
    analysisTaskId.value = response.task_id
    analysisContentHash.value = submittedContentHash
    analysisTaskStatus.value = response.status
    analysisTaskProgress.value = 0
    analysisTaskStage.value = 'QUEUED'
    saveTaskReference()
    void pollAnalysisTask(id)
  } catch (error) {
    analysisTaskError.value = error instanceof Error ? error.message : '解析任务提交失败'
    if (Number((error as { statusCode?: number })?.statusCode) === 400) void loadAnalysisPromptOptions()
  } finally {
    analysisSubmitting.value = false
  }
}

async function pollAnalysisTask(project: number) {
  const token = ++analysisPollToken
  if (analysisPollTimer !== null) {
    window.clearTimeout(analysisPollTimer)
    analysisPollTimer = null
  }
  let networkFailures = 0
  for (let attempt = 0; attempt < 120 && token === analysisPollToken; attempt += 1) {
    if (import.meta.client && document.visibilityState === 'hidden') {
      await new Promise((resolve) => { analysisPollTimer = window.setTimeout(resolve, 2000) })
      continue
    }
    try {
      const response = await http.get<AnalysisTaskResponse>(`/projects/${project}/script/analysis-tasks/${analysisTaskId.value}`)
      if (token !== analysisPollToken) return
      networkFailures = 0
      analysisTaskStatus.value = response.status
      analysisContentHash.value = response.content_hash || analysisContentHash.value
      analysisTaskProgress.value = response.progress
      analysisTaskStage.value = response.stage || ''
      analysisTypeResults.value = response.type_results || []
      analysisAdoptedTypeNames.value = response.adopted_types || []
      analysisAdoptionStatus.value = response.result_status || ''
      if (response.status === 'SUCCEEDED') {
        analysisResult.value = response.result || null
        analysisDetailsVisible.value = false
        scriptStats.value = { ...scriptStats.value, ...response.stats }
        parsed.value = true
        analysisAdoptionTypes.value = adoptableTypeResults.value.map((item) => item.type)
        analysisModalVisible.value = false
        analysisSummaryVisible.value = true
        message.success('解析结果已生成，请确认是否采纳')
        return
      }
      if (response.status === 'FAILED' || response.status === 'CANCELLED') {
        analysisTaskError.value = response.error?.message || (response.status === 'CANCELLED' ? '解析任务已取消' : '剧本解析失败')
        return
      }
    } catch (error) {
      if (token !== analysisPollToken) return
      networkFailures += 1
      analysisTaskError.value = error instanceof Error ? error.message : '解析任务查询失败'
      await new Promise((resolve) => { analysisPollTimer = window.setTimeout(resolve, Math.min(5000, 1000 * 2 ** Math.min(networkFailures - 1, 3))) })
      continue
    }
    const delay = attempt < 30 ? 1000 : attempt < 90 ? 2000 : 5000
    await new Promise((resolve) => { analysisPollTimer = window.setTimeout(resolve, delay) })
  }
  if (token === analysisPollToken) message.info('解析任务仍在运行，可稍后返回此页查看结果')
}

async function cancelAnalysisTask() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0 || !analysisTaskId.value) return
  try {
    const response = await http.post<{ status: string; can_cancel: boolean }>(`/projects/${id}/tasks/${analysisTaskId.value}/cancel`)
    analysisTaskStatus.value = response.status
    if (response.status === 'CANCELLED') analysisTaskError.value = '解析任务已取消'
  } catch (error) {
    message.error(error instanceof Error ? error.message : '取消解析任务失败')
  }
}

async function retryAnalysisTask() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0 || !analysisTaskId.value || analysisTaskStatus.value !== 'FAILED') return
  analysisRetrying.value = true
  try {
    const response = await http.post<{ task_id: string; status: string }>(`/projects/${id}/script/analysis-tasks/${analysisTaskId.value}/retry`)
    analysisPollToken += 1
    if (analysisPollTimer !== null) window.clearTimeout(analysisPollTimer)
    analysisPollTimer = null
    analysisTaskId.value = response.task_id
    analysisTaskStatus.value = response.status
    analysisTaskError.value = ''
    analysisTaskProgress.value = 0
    analysisTaskStage.value = 'QUEUED'
    analysisTypeResults.value = []
    analysisAdoptionTypes.value = []
    analysisAdoptedTypeNames.value = []
    analysisAdoptionStatus.value = ''
    saveTaskReference()
    void pollAnalysisTask(id)
    message.success('已发起重试')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '发起重试失败')
  } finally {
    analysisRetrying.value = false
  }
}

async function adoptAnalysisResult() {
  const id = Number(projectId.value)
  if (!Number.isInteger(id) || id <= 0 || !analysisTaskId.value || !currentVersionId.value || !analysisAdoptionTypes.value.length) return
  analysisAdopting.value = true
  try {
    const selectionFingerprint = [...analysisAdoptionTypes.value].sort().join(',')
    if (analysisAdoptionFingerprint.value !== selectionFingerprint) {
      analysisAdoptionFingerprint.value = selectionFingerprint
      analysisAdoptionKey.value = requestKey()
    }
    const payload = new FormData()
    payload.append('version_id', String(currentVersionId.value))
    analysisAdoptionTypes.value.forEach((type) => payload.append('selected_asset_types', type))
    const response = await http.post<AnalysisAdoptionResponse, FormData>(`/projects/${id}/script/analysis-tasks/${analysisTaskId.value}/adopt`, payload, {
      headers: { 'Idempotency-Key': analysisAdoptionKey.value },
    })
    analysisAdoptionKey.value = ''
    analysisAdoptionFingerprint.value = ''
    analysisAdoptionStatus.value = response.adoption_status
    analysisAdoptedTypeNames.value = [...new Set([...analysisAdoptedTypeNames.value, ...response.selected_asset_types])]
    analysisAdoptionTypes.value = adoptableTypeResults.value.map((item) => item.type)
    await Promise.all([loadEpisodes(projectId.value), loadLibrary(projectId.value)])
    analysisSummaryVisible.value = false
    message.success(`已录入 ${response.created_count} 项资产${response.skipped_count ? `，跳过 ${response.skipped_count} 项` : ''}`)
    if (response.adoption_status === 'ADOPTED') clearTaskReference()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '采纳解析结果失败')
  } finally {
    analysisAdopting.value = false
  }
}

async function discardAnalysisResult() {
  const id = Number(projectId.value)
  // 已采纳或无任务引用时没有可放弃的结果，直接关闭即可。
  if (!Number.isInteger(id) || id <= 0 || !analysisTaskId.value || analysisAdoptionStatus.value === 'ADOPTED') {
    analysisSummaryVisible.value = false
    return
  }
  analysisDiscarding.value = true
  try {
    await http.post(`/projects/${id}/script/analysis-tasks/${analysisTaskId.value}/discard`)
    analysisAdoptionStatus.value = 'DISCARDED'
    clearTaskReference()
    analysisSummaryVisible.value = false
  } catch (error) {
    message.error(error instanceof Error ? error.message : '放弃解析结果失败')
  } finally {
    analysisDiscarding.value = false
  }
}

function assetTypeLabel(type: AnalysisJob['type']) {
  return type === 'CHARACTER' ? '角色' : type === 'SCENE_BACKGROUND' ? '场景' : '道具'
}

async function contentHash(content: string) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(content))
  return `sha256:${Array.from(new Uint8Array(digest)).map((value) => value.toString(16).padStart(2, '0')).join('')}`
}

function requestKey() {
  return crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random().toString(16).slice(2)}`
}

function taskStorageKey() {
  return `script-analysis:${projectId.value}:${episodeId.value}`
}

function saveTaskReference() {
  if (!import.meta.client || !analysisTaskId.value) return
  window.sessionStorage.setItem(taskStorageKey(), JSON.stringify({
    task_id: analysisTaskId.value,
    project_id: projectId.value,
    episode_id: episodeId.value,
    version_id: currentVersionId.value,
    content_hash: analysisContentHash.value,
  }))
}

function clearTaskReference() {
  if (import.meta.client) window.sessionStorage.removeItem(taskStorageKey())
}

async function restoreAnalysisTask() {
  if (!import.meta.client || !episodeQuery.value?.episode_id) return
  const stored = window.sessionStorage.getItem(taskStorageKey())
  if (!stored) return
  let reference: { task_id?: string; project_id?: string; episode_id?: string; version_id?: number; content_hash?: string }
  try {
    reference = JSON.parse(stored) as typeof reference
  } catch {
    reference = { task_id: stored }
  }
  if (!reference.task_id || (reference.project_id && reference.project_id !== projectId.value) || (reference.episode_id && reference.episode_id !== episodeId.value)) return
  analysisTaskId.value = reference.task_id
  if (reference.version_id) currentVersionId.value = reference.version_id
  analysisContentHash.value = reference.content_hash || ''
  try {
    await pollAnalysisTask(Number(projectId.value))
  } catch {
    analysisTaskError.value = '任务恢复失败，请从任务中心重新查看'
  }
}

function extractParseError(error: unknown, fallback: string): ParseError {
  const value = error as { message?: string; data?: { data?: { error?: ParseError }; message?: string } }
  const structured = value.data?.data?.error
  return structured ? { ...structured, message: value.data?.message || value.message || fallback } : { message: value.data?.message || value.message || fallback }
}

function generateScript() {
  const prompt = generationPrompt.value.trim()
  if (!prompt || generating.value) return
  generating.value = true
  generatedNotice.value = ''
  window.setTimeout(() => {
    const style = selectedStyle.value ? `【${selectedStyle.value}】` : ''
    scriptText.value = `${style}${prompt}\n\n第一场  夜·外景\n人物在城市灯火与雨幕之间相遇，旧日的秘密被重新揭开。\n\n第二场  日·内景\n两人面对选择，决定一起寻找答案。`
    parsed.value = false
    generatedNotice.value = '已生成初稿，可继续编辑后点击“开始解析”。'
    generating.value = false
    message.success('AI 剧本初稿已生成')
  }, 420)
}

function readFile(source?: File) {
  if (!source) return
  if (!/\.txt$/i.test(source.name)) {
    message.warning('当前仅支持读取 .txt 文件，.docx 请先转换为纯文本')
    return
  }
  source.text().then((text) => {
    scriptText.value = text
    parsed.value = false
    message.success(`已导入 ${source.name}`)
  }).catch(() => message.error('剧本文件读取失败'))
}

function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  readFile(input.files?.[0])
  input.value = ''
}

function onDrop(event: DragEvent) {
  isDragging.value = false
  readFile(event.dataTransfer?.files?.[0])
}

function goPrevious() {
  navigateTo(projectId.value ? `/projects/${projectId.value}` : '/projects')
}

function goNext() {
  if (!parsed.value) return
  const prefix = episodeId.value ? `/projects/${projectId.value}/episodes/${episodeId.value}/workflow` : `/projects/${projectId.value}/workflow`
  navigateTo(`${prefix}/tasks`)
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch {
    // Keep the local project cache available when the API is unavailable.
  }
  await Promise.all([loadScript(), loadProviderConfigs(), loadAnalysisPromptOptions()])
  await restoreAnalysisTask()
})

onBeforeUnmount(() => {
  analysisPollToken += 1
  if (analysisPollTimer !== null) window.clearTimeout(analysisPollTimer)
  analysisPollTimer = null
})

watch(() => activeProject.value?.id, (next, previous) => {
  if (next && next !== previous) {
    loadScript()
    void loadAnalysisPromptOptions()
  }
})

watch(episodeId, (next, previous) => {
  if (next && next !== previous) {
    analysisPollToken += 1
    analysisTaskId.value = ''
    analysisTaskStatus.value = ''
    analysisTaskError.value = ''
    analysisAdoptionStatus.value = ''
    analysisTypeResults.value = []
    analysisAdoptionTypes.value = []
    loadScript()
    void restoreAnalysisTask()
  }
})

</script>

<style scoped>
.script-page {
  font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
}

.script-dropzone:focus-within {
  border-color: #9b91f5;
  box-shadow: 0 0 0 3px #eeecff;
}

.script-footer {
  padding-bottom: env(safe-area-inset-bottom);
}

</style>
