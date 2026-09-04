<template>
  <main class="min-h-screen bg-[#f5f6fa] px-4 py-7 text-[#20283b] sm:px-6">
    <div class="mx-auto max-w-[1177px]">
      <header>
        <p class="m-0 text-xs font-bold tracking-[0.8px] text-[#5c52ee]">03 / 07 · PRODUCTION NODE</p>
        <div class="mt-6 flex flex-col items-start justify-between gap-5 sm:flex-row sm:items-end">
          <div>
            <h1 class="m-0 text-[31px] font-medium leading-[1.18]">每集资产管理</h1>
            <p class="mt-2 text-[13px] text-[#8a94aa]">角色、场景背景、道具与服饰按当前剧集独立维护。</p>
            <p v-if="hasEpisode && activeEpisode" class="mt-2 text-xs text-[#6654ee]">当前剧集：{{
                activeEpisode.title
              }}</p>
          </div>
          <div class="flex w-full gap-2 sm:w-auto">
            <n-button :loading="saving" class="!h-[42px] !rounded-[10px]" secondary @click="saveDraft">保存草稿
            </n-button>
            <n-button v-if="hasEpisode" class="!h-[42px] !rounded-[10px] !border-[#ff9b4a] !text-[#e87517]" ghost
                      @click="openExtraction">AI 提取资产
            </n-button>
            <n-button class="!h-[42px] !rounded-[10px] !border-[#665bff] !text-[#5b4ff0]" ghost @click="openCreate">
              添加资产
            </n-button>
          </div>
        </div>
      </header>
      <ProductionSteps active="assets"/>
      <section class="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,756px)_minmax(330px,404px)]">
        <n-card :content-style="{ padding: '0 25px 24px' }" class="asset-panel !rounded-[17px] !border-[#dfe3eb]">
          <div
              class="flex min-h-[127px] flex-col items-start justify-between gap-5 border-b border-[#e8eaf0] px-2 py-5 sm:flex-row sm:items-center sm:px-[23px]">
            <div><h2 class="m-0 text-lg font-medium">{{ hasEpisode ? '本集资产清单' : '项目资产清单' }}</h2>
              <p class="mt-2 text-xs text-[#8994a8]">编辑后即时保存，状态用于追踪资产准备进度。</p></div>
            <n-button v-if="hasEpisode" class="!rounded-[10px] !border-[#ffcf9f] !text-[#e87517]" ghost
                      @click="openExtraction">提取场景/角色/道具
            </n-button>
            <n-button class="!rounded-[10px] !border-[#d8d2ff] !text-[#5b4ff0]" ghost @click="openCreate">＋ 添加资产
            </n-button>
          </div>
          <div class="flex flex-col gap-4 py-5 sm:flex-row sm:items-center sm:justify-between">
            <n-button-group class="max-w-full overflow-x-auto">
              <n-button v-for="filter in filters" :key="filter.key"
                        :color="selectedFilter === filter.key ? '#6654ee' : undefined"
                        :type="selectedFilter === filter.key ? 'primary' : 'default'"
                        @click="selectedFilter = filter.key">{{ filter.label }}
              </n-button>
            </n-button-group>
            <n-input v-model:value="search" class="w-full sm:!w-[190px]" clearable placeholder="搜索名称或描述"/>
          </div>
          <div v-if="loading" class="py-16 text-center text-sm text-[#929bad]">正在加载资产...</div>
          <div v-else-if="!filteredAssets.length"
               class="border border-dashed border-[#dfe3eb] px-5 py-16 text-center text-sm text-[#929bad]">
            暂无资产，点击“添加资产”开始维护。
          </div>
          <div v-else class="grid grid-cols-1 gap-[14px] sm:grid-cols-2">
            <n-card v-for="asset in filteredAssets" :key="asset.id" :content-style="{ padding: 0 }"
                    class="min-h-[174px] !overflow-hidden !rounded-xl !border-[#dfe3eb]">
              <div class="flex min-h-[174px]">
                <div :style="{ background: colorByType[asset.type] || colorByType.PROP }"
                     class="flex w-[106px] shrink-0 flex-col items-center justify-center text-white">
                  <n-tag :bordered="false" class="self-start !ml-2 !bg-[rgba(31,40,59,0.26)] !text-white" size="small">
                    {{ typeLabel[asset.type] }}
                  </n-tag>
                  <strong class="mt-3 text-[32px] leading-none">{{ asset.name.slice(0, 1) }}</strong></div>
                <div class="min-w-0 flex-1 px-3 pb-3 pt-3">
                  <div class="flex items-start justify-between gap-2"><strong class="truncate text-[13px] font-medium">{{
                      asset.name
                    }}</strong>
                    <n-tag :type="asset.status === 'LOCKED' || asset.status === 'READY' ? 'success' : 'warning'"
                           size="small">
                      {{ statusLabel[asset.status] }}
                    </n-tag>
                  </div>
                  <p class="mt-2 line-clamp-2 min-h-[32px] text-[11px] text-[#7e899e]">{{
                      asset.description || '暂无描述'
                    }}</p><small class="mt-2 block truncate text-[10px] text-[#929bad]">{{
                    asset.reference_url || '未添加参考图链接'
                  }}</small>
                  <div class="mt-3 flex gap-2">
                    <n-button class="!text-[#5a4ef0]" size="tiny" text @click="openEdit(asset)">编辑详情</n-button>
                    <n-button class="!text-[#a34d4d]" size="tiny" text @click="removeAsset(asset)">删除</n-button>
                  </div>
                </div>
              </div>
            </n-card>
          </div>
          <n-alert v-if="assets.length" :show-icon="false" class="mt-4 !rounded-[10px] !border-[#f3dfb5] !bg-[#fff9ed]"
                   type="warning"><strong class="text-xs text-[#ae7620]">资产完整度 {{ completeness }}%</strong><span
              class="ml-3 text-[11px] text-[#9a7b48]">{{
              completeness === 100 ? '本集资产已全部就绪。' : '仍有资产处于草稿或待生成状态。'
            }}</span></n-alert>
        </n-card>
        <AssistantPanel :message="assistantMessage" :suggestions="assistantSuggestions" title="资产清单助手"
                        @send="updateAssistant"/>
      </section>
      <n-modal v-model:show="extractionVisible" :mask-closable="false" class="w-[min(94vw,620px)]" preset="card"
               title="提取场景/角色/道具">
        <p class="-mt-2 text-xs text-[#8a94aa]">根据剧本内容，AI 将按选择的模型分别提取资产。</p>
        <div class="mt-4 space-y-3">
          <div v-for="job in extractionJobs" :key="job.type"
               class="rounded-[12px] border border-[#ffb36d] bg-[#fff9f1] p-3.5">
            <div class="flex items-start gap-3">
              <n-checkbox v-model:checked="job.enabled" class="mt-1"/>
              <div class="min-w-0 flex-1">
                <div class="flex flex-wrap items-start justify-between gap-2">
                  <div><span class="text-[11px] text-[#8a94aa]">{{ job.agentLabel }}</span><strong
                      class="mt-0.5 block text-sm text-[#20283b]">{{ job.label }}</strong></div>
                  <span class="text-[11px] text-[#e87517]">3 积分</span>
                </div>
                <p class="mt-2 text-[11px] leading-5 text-[#7e899e]">{{ job.description }}</p>
                <div class="mt-3 border-t border-[#f2dec8] pt-3">
                  <label class="mb-1.5 block text-[11px] text-[#8994a8]">选择模型</label>
                  <n-select v-model:value="job.modelKey" :disabled="!modelOptions.length" :options="modelOptions"
                            placeholder="请先配置模型" size="small"/>
                </div>
              </div>
            </div>
          </div>
        </div>
        <div v-if="extractionError"
             class="mt-4 rounded-[9px] border border-[#f1c8c8] bg-[#fff7f7] px-3 py-2 text-xs text-[#a34d4d]"
             role="alert">
          <strong class="block font-medium">提取失败：{{ extractionError.message }}</strong>
          <div class="mt-1.5 grid grid-cols-1 gap-1 text-[11px] text-[#8e6262] sm:grid-cols-2">
            <span>错误代码：{{
                extractionError.error_code ?? '未返回'
              }}</span><span>服务提供商：{{ extractionError.provider || '未返回' }}</span>
            <span class="sm:col-span-2">请求 ID：{{ extractionError.request_id || '未返回' }}</span>
          </div>
          <pre v-if="extractionError.response_content"
               class="mt-2 max-h-28 overflow-auto whitespace-pre-wrap break-words rounded-md bg-white px-2 py-1.5 text-[10px] leading-4 text-[#765858]">{{
              extractionError.response_content
            }}</pre>
        </div>
        <template #footer>
          <div class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between"><span
              class="text-xs text-[#8994a8]">预估消耗：{{ selectedExtractionCount * 3 }} 积分</span>
            <div class="flex justify-end gap-2">
              <n-button :disabled="extracting" @click="extractionVisible = false">取消</n-button>
              <n-button :disabled="!selectedExtractionCount || !modelOptions.length" :loading="extracting" color="#16a34a"
                        type="primary" @click="startExtraction">开始提取
              </n-button>
            </div>
          </div>
        </template>
      </n-modal>
      <n-modal v-model:show="imageGenerationVisible" :mask-closable="false" class="w-[min(92vw,560px)]" preset="card"
               title="AI 生成参考图">
        <n-form label-placement="top">
          <p class="-mt-2 mb-3 text-xs text-[#8a94aa]">生成目标：{{ imageGenerationSlots.map((slot) => generationSlotLabel[slot]).join('、') }}</p>
          <n-form-item label="生成模式">
            <n-select v-model:value="imageGenerationMode" :options="imageGenerationModeOptions"/>
          </n-form-item>
          <n-form-item label="生成模型">
            <n-select v-model:value="imageGenerationModelKey" :options="imageModelOptions" placeholder="请选择图片模型"/>
          </n-form-item>
          <p class="m-0 text-[11px] leading-5 text-[#8994a8]">提示词和参考图由服务端从当前资产档案读取，生成结果会先保存到服务端文件库，再回填到当前资产。</p>
        </n-form>
        <template #footer>
          <div class="flex justify-end gap-2">
            <n-button :disabled="imageGenerating" @click="imageGenerationVisible = false">取消</n-button>
            <n-button :loading="imageGenerating" color="#6654ee" type="primary" @click="confirmImageGeneration">开始生成
            </n-button>
          </div>
        </template>
      </n-modal>
      <n-modal v-model:show="editorVisible" :mask-closable="false" :title="editing ? '编辑资产详情' : '添加本集资产'"
               class="w-[min(92vw,560px)]" preset="card">
        <n-form label-placement="top">
          <n-form-item label="名称">
            <n-input v-model:value="draft.name" placeholder="如：林夏、雨夜街道、旧相机"/>
          </n-form-item>
          <n-form-item label="类型">
            <n-select v-model:value="draft.type" :options="typeOptions"/>
          </n-form-item>
          <n-form-item label="描述">
            <n-input v-model:value="draft.description" :autosize="{ minRows: 3, maxRows: 6 }" placeholder="外观、用途、出场要求或连续性备注"
                     type="textarea"/>
          </n-form-item>
          <n-form-item label="参考图链接">
            <n-input v-model:value="draft.reference_url" placeholder="可选，填写图片或素材地址"/>
          </n-form-item>
          <n-form-item label="状态">
            <n-select v-model:value="draft.status" :options="statusOptions"/>
          </n-form-item>
        </n-form>
        <template #footer>
          <div class="flex justify-end gap-2">
            <n-button @click="editorVisible = false">取消</n-button>
            <n-button :loading="saving" type="primary" @click="submitAsset">保存</n-button>
          </div>
        </template>
      </n-modal>
      <n-modal :show="characterVisible" :mask-closable="false" class="w-[min(96vw,760px)]" preset="card"
               @update:show="handleCharacterVisibility">
        <template #header>
          <div class="flex min-w-0 items-baseline gap-2 text-[16px] font-medium"><span
              class="truncate">角色详情: {{ characterForm.name || characterName }}</span>
            <template v-if="characterForm.identity_title"><span class="text-[#c3c9d6]">|</span><span
                class="shrink-0 text-[13px] font-normal text-[#8a94aa]">{{ characterForm.identity_title }}</span>
            </template>
          </div>
        </template>
        <template #header-extra>
          <div class="flex items-center gap-2">
            <n-button class="!rounded-[8px]" size="small" @click="openImageGeneration(['front'])">去生图</n-button>
            <n-button class="!rounded-[8px]" color="#5c52ee" size="small" type="primary" @click="openCharacterEnrichment">内容丰富</n-button>
            <n-button class="!rounded-[8px]" color="#f0820d" size="small" type="primary"
                      @click="notifyGenerationPending">训练
            </n-button>
          </div>
        </template>
        <div v-if="characterLoading" class="py-16 text-center text-sm text-[#929bad]">正在加载角色详情...</div>
        <div v-else class="space-y-5">
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">基本信息</h3>
            <div class="mt-2.5 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <n-input v-model:value="characterForm.name" :maxlength="100" placeholder="角色名称" size="small"/>
              <n-input v-model:value="characterForm.identity_title" :maxlength="200" placeholder="身份/职业" size="small"/>
            </div>
            <div class="mt-2 grid grid-cols-1 gap-2 sm:grid-cols-2">
              <n-input v-model:value="characterForm.reference_url" :maxlength="1000" placeholder="参考图链接" size="small"/>
              <n-select v-model:value="characterForm.status" :options="statusOptions" placeholder="资产状态"/>
            </div>
            <div class="mt-2.5 flex flex-wrap items-center gap-2">
              <span class="text-xs text-[#8994a8]">卡片：</span>
              <n-input v-model:value="characterForm.role_tag" :maxlength="50" class="tag-input tag-primary !w-[76px]" placeholder="角色定位"
                       size="small"/>
              <n-input v-model:value="characterForm.gender" :maxlength="50" class="tag-input !w-[60px]" placeholder="性别"
                       size="small"/>
              <n-input v-model:value="characterForm.age_stage" :maxlength="50" class="tag-input !w-[84px]" placeholder="年龄段"
                       size="small"/>
              <n-input v-model:value="characterForm.age_note" :maxlength="50" class="tag-input !w-[84px]" placeholder="年龄备注"
                       size="small"/>
            </div>
            <div class="mt-3 flex justify-end">
              <n-checkbox v-model:checked="characterForm.is_real_person">该角色为真人</n-checkbox>
            </div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">文字描述</h3>
            <n-input v-model:value="characterForm.description" :autosize="{ minRows: 3, maxRows: 8 }" :maxlength="100000" show-count
                     :bordered="false" class="plain-area mt-2"
                     placeholder="填写角色整体描述、性格、关系和出场要求" type="textarea"/>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">人脸 <span
                class="text-[11px] font-normal text-[#b6bdcc]">(Seedance 2.0)</span></h3>
            <n-input v-model:value="characterForm.face_description" :autosize="{ minRows: 3, maxRows: 8 }" :maxlength="100000" show-count
                     :bordered="false" class="plain-area mt-2"
                     placeholder="填写角色面部特征描述，供图像生成参考" type="textarea"/>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">服装</h3>
            <n-input v-model:value="characterForm.costume_description" :autosize="{ minRows: 3, maxRows: 8 }" :maxlength="100000" show-count
                     :bordered="false" class="plain-area mt-2"
                     placeholder="填写角色服装与造型描述，供图像生成参考" type="textarea"/>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">
              三视图</h3>
              <n-button class="!rounded-[8px]" color="#f0820d" secondary size="small" @click="openImageGeneration(['front', 'side', 'back'])">
                生成三视图
              </n-button>
            </div>
            <div class="mt-3 flex flex-wrap gap-5">
              <div v-for="view in threeViewSlots" :key="view.key" class="flex flex-col items-center gap-1.5">
                <div :aria-label="`${view.label}视图，点击上传或选择`" :class="{ 'is-busy': uploading && uploadSlot === view.key }" class="image-slot"
                     role="button" tabindex="0" @click="triggerImageUpload(view.key)"
                     @keydown.enter.prevent="triggerImageUpload(view.key)" @keydown.space.prevent="triggerImageUpload(view.key)">
                  <img v-if="characterImages[view.key]?.url" :src="characterImages[view.key]?.url || ''"
                       :alt="`${characterForm.name || characterName} ${view.label}视图`" class="h-full w-full object-cover">
                  <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                    <svg fill="none" height="26" stroke="currentColor" stroke-linecap="round" stroke-width="1.6" viewBox="0 0 24 24"
                         width="26">
                      <circle cx="12" cy="8.2" r="3.4"/>
                      <path d="M5.5 19.2c1.1-3.2 3.6-4.8 6.5-4.8s5.4 1.6 6.5 4.8"/>
                    </svg>
                    <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
                  </div>
                  <button v-if="characterImages[view.key]" :aria-label="`移除${view.label}视图`" class="slot-remove" type="button"
                          @click.stop="clearCharacterImage(view.key)">×
                  </button>
                  <div v-if="uploading && uploadSlot === view.key" class="slot-busy-mask">上传中...</div>
                </div>
                <span class="text-[11px] text-[#8a94aa]">{{ view.label }}</span>
              </div>
            </div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">合并三视图</h3>
            <div aria-label="合并三视图，点击上传或选择" :class="{ 'is-busy': uploading && uploadSlot === 'merged' }" class="image-slot image-slot--wide mt-3"
                 role="button" tabindex="0" @click="triggerImageUpload('merged')"
                 @keydown.enter.prevent="triggerImageUpload('merged')" @keydown.space.prevent="triggerImageUpload('merged')">
              <img v-if="characterImages.merged?.url" :src="characterImages.merged.url"
                   :alt="`${characterForm.name || characterName} 合并三视图`" class="h-full w-full object-contain">
              <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                <svg fill="none" height="26" stroke="currentColor" stroke-linecap="round" stroke-width="1.6" viewBox="0 0 24 24"
                     width="26">
                  <circle cx="12" cy="8.2" r="3.4"/>
                  <path d="M5.5 19.2c1.1-3.2 3.6-4.8 6.5-4.8s5.4 1.6 6.5 4.8"/>
                </svg>
                <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
              </div>
              <button v-if="characterImages.merged" aria-label="移除合并三视图" class="slot-remove" type="button"
                      @click.stop="clearCharacterImage('merged')">×
              </button>
              <div v-if="uploading && uploadSlot === 'merged'" class="slot-busy-mask">上传中...</div>
            </div>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">
              表情图</h3>
              <n-button class="!rounded-[8px]" color="#f0820d" secondary size="small" @click="openImageGeneration(['expression'])">
                生成表情图
              </n-button>
            </div>
            <div v-if="!characterImages.expressions.length" class="mt-3 py-8 text-center text-[13px] text-[#b6bdcc]">
              暂无数据
            </div>
            <div v-else class="mt-3 flex flex-wrap gap-3"><div v-for="(item, index) in characterImages.expressions"
                                                               :key="item.file_id || index" class="relative">
              <img :src="item.url || ''" :alt="`${characterForm.name || characterName} 表情参考图 ${index + 1}`" class="image-slot !h-[96px] !w-[96px] cursor-default">
              <button :aria-label="`移除表情参考图 ${index + 1}`" class="slot-remove" type="button" @click="clearExpressionImage(index)">×</button>
            </div></div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">元素搭配</h3>
            <div class="mt-3 grid grid-cols-1 gap-3 sm:grid-cols-3">
              <div v-for="field in elementFields" :key="field.key"
                   class="rounded-[10px] border border-[#e8eaf0] bg-white px-3 py-2">
                <span class="text-xs text-[#5c6778]">{{ field.label }}</span>
                <n-input v-model:value="characterForm[field.key]" :bordered="false" :maxlength="100" class="mt-0.5 !px-0" placeholder="—"
                         size="small"/>
              </div>
            </div>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">配音绑定</h3>
            <div
                class="mt-3 flex items-center justify-between rounded-[10px] border border-[#e8eaf0] bg-white px-3.5 py-2">
              <span :class="characterForm.voice_asset_id ? 'text-[#20283b]' : 'text-[#b6bdcc]'" class="text-[13px]">{{
                  voiceBindingLabel
                }}</span>
              <n-popselect v-model:value="characterForm.voice_asset_id" :options="voiceOptions" trigger="click">
                <n-button class="!text-[#5a4ef0]" quaternary size="tiny">选择声线</n-button>
              </n-popselect>
            </div>
          </section>
        </div>
        <template #footer>
          <div class="flex justify-end gap-2">
            <span v-if="!characterLoading && characterDirty" aria-live="polite" class="mr-auto self-center text-xs text-[#d97706]">有未保存修改</span>
            <n-button @click="closeCharacterDetail">取消</n-button>
            <n-button :disabled="!characterDirty || characterLoading" :loading="characterSaving" color="#f0820d" type="primary" @click="saveCharacterProfile">保存
            </n-button>
          </div>
        </template>
      </n-modal>
      <n-modal v-model:show="enrichmentVisible" :mask-closable="false" class="w-[min(92vw,620px)]" preset="card" title="角色内容丰富">
        <p class="-mt-2 text-xs leading-5 text-[#8994a8]">生成角色卡草稿，结果需要确认后才会写入当前角色资料。</p>
        <n-form class="mt-4" label-placement="top">
          <n-form-item label="生成模型" required>
            <n-select v-model:value="enrichmentModelKey" :options="modelOptions" placeholder="请选择对话模型" />
          </n-form-item>
        </n-form>
        <div v-if="enrichmentTask" class="rounded-[9px] border border-[#e7e9f1] bg-[#fafbfe] p-3 text-xs text-[#687388]">
          <div class="flex items-center justify-between"><span>任务状态</span><strong>{{ enrichmentTask.status }}</strong></div>
          <div v-if="enrichmentTask.result_status" class="mt-2 flex items-center justify-between"><span>结果状态</span><strong>{{ enrichmentTask.result_status }}</strong></div>
          <pre v-if="enrichmentTask.result" class="mt-3 max-h-56 overflow-auto whitespace-pre-wrap break-words text-[10px] leading-4">{{ JSON.stringify(enrichmentTask.result, null, 2) }}</pre>
        </div>
        <template #footer>
          <div class="flex justify-end gap-2">
            <n-button :disabled="enrichmentLoading" @click="enrichmentVisible = false">关闭</n-button>
            <n-button v-if="enrichmentTask?.result_status === 'STAGED'" :loading="enrichmentLoading" type="primary" @click="adoptCharacterEnrichment">采纳角色卡</n-button>
            <n-button v-else :disabled="!enrichmentModelKey" :loading="enrichmentLoading" color="#5c52ee" type="primary" @click="startCharacterEnrichment">开始生成</n-button>
          </div>
        </template>
      </n-modal>
      <n-modal v-model:show="sceneVisible" :mask-closable="false" class="w-[min(96vw,760px)]" preset="card">
        <template #header>
          <div class="flex min-w-0 items-baseline gap-2 text-[16px] font-medium"><span
              class="truncate">场景详情: {{ sceneName }}</span>
            <template v-if="sceneForm.identity_title"><span class="text-[#c3c9d6]">|</span><span
                class="shrink-0 text-[13px] font-normal text-[#8a94aa]">{{ sceneForm.identity_title }}</span></template>
          </div>
        </template>
        <template #header-extra>
          <div class="flex items-center gap-2">
            <n-button :loading="sceneSaving" class="!rounded-[8px]" color="#3f4657" size="small" type="primary"
                      @click="saveSceneProfile">保存
            </n-button>
            <n-button class="!rounded-[8px]" size="small" @click="openImageGeneration(['main'])">去生图</n-button>
            <n-button class="!rounded-[8px]" color="#f0820d" ghost size="small" @click="notifyGenerationPending">训练
            </n-button>
          </div>
        </template>
        <div v-if="sceneLoading" class="py-16 text-center text-sm text-[#929bad]">正在加载场景详情...</div>
        <div v-else class="space-y-5">
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#20283b]">基本信息</h3>
            <div class="mt-2.5 flex flex-wrap items-center gap-2">
              <span class="text-xs text-[#8994a8]">地点：</span>
              <n-input v-model:value="sceneForm.location" class="!w-[104px]" placeholder="内景/外景" size="small"/>
              <span class="text-xs text-[#8994a8]">时间：</span>
              <n-input v-model:value="sceneForm.time" class="!w-[104px]" placeholder="如：黄昏" size="small"/>
              <span class="text-xs text-[#8994a8]">氛围：</span>
              <n-input v-model:value="sceneForm.atmosphere" class="!w-[180px]" placeholder="多标签以逗号分隔"
                       size="small"/>
            </div>
          </section>
          <section>
            <n-input v-model:value="sceneForm.description" :autosize="{ minRows: 4, maxRows: 10 }" :bordered="false"
                     class="plain-area" placeholder="填写场景整体视觉描述，供图像生成参考" type="textarea"/>
          </section>
          <section>
            <div :class="{ 'is-busy': uploading && uploadSlot === 'main' }" class="image-slot image-slot--hero"
                 @click="triggerImageUpload('main')">
              <img v-if="sceneImages.main?.url" :src="sceneImages.main.url" alt="" class="h-full w-full object-cover">
              <div v-else class="flex h-full w-full flex-col items-center justify-center gap-2 text-[#c3c9d6]">
                <svg fill="none" height="30" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.6"
                     viewBox="0 0 24 24" width="30">
                  <path d="M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11Z"/>
                  <circle cx="12" cy="10" r="2.6"/>
                </svg>
                <span class="text-[11px]">点击上传或选择</span>
              </div>
              <button v-if="sceneImages.main" class="slot-remove" type="button" @click.stop="clearSceneImage('main')">
                ×
              </button>
              <div v-if="uploading && uploadSlot === 'main'" class="slot-busy-mask">上传中...</div>
            </div>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">
              四视图</h3>
              <n-button class="!rounded-[8px]" color="#f0820d" secondary size="small" @click="openImageGeneration(['east', 'south', 'west', 'north'])">
                生成四视图
              </n-button>
            </div>
            <div class="mt-3 flex flex-wrap gap-5">
              <div v-for="view in sceneViewSlots" :key="view.key" class="flex flex-col items-center gap-1.5">
                <div :class="{ 'is-busy': uploading && uploadSlot === view.key }" class="image-slot image-slot--scene"
                     @click="triggerImageUpload(view.key)">
                  <img v-if="sceneImages[view.key]?.url" :src="sceneImages[view.key]?.url || ''"
                       alt="" class="h-full w-full object-cover">
                  <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                    <svg fill="none" height="24" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.6"
                         viewBox="0 0 24 24" width="24">
                      <path d="M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11Z"/>
                      <circle cx="12" cy="10" r="2.6"/>
                    </svg>
                    <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
                  </div>
                  <button v-if="sceneImages[view.key]" class="slot-remove" type="button"
                          @click.stop="clearSceneImage(view.key)">×
                  </button>
                  <div v-if="uploading && uploadSlot === view.key" class="slot-busy-mask">上传中...</div>
                </div>
                <span class="text-[11px] text-[#8a94aa]">{{ view.label }}</span>
              </div>
            </div>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">
              多角度</h3>
              <n-button class="!rounded-[8px]" color="#f0820d" secondary size="small" @click="openImageGeneration(['longShot', 'fullShot', 'mediumShot', 'closeShot'])">
                生成多角度
              </n-button>
            </div>
            <div class="mt-3 flex flex-wrap gap-5">
              <div v-for="angle in sceneAngleSlots" :key="angle.key" class="flex flex-col items-center gap-1.5">
                <div :class="{ 'is-busy': uploading && uploadSlot === angle.key }" class="image-slot image-slot--scene"
                     @click="triggerImageUpload(angle.key)">
                  <img v-if="sceneImages[angle.key]?.url" :src="sceneImages[angle.key]?.url || ''"
                       alt="" class="h-full w-full object-cover">
                  <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                    <svg fill="none" height="24" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.6"
                         viewBox="0 0 24 24" width="24">
                      <path d="M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11Z"/>
                      <circle cx="12" cy="10" r="2.6"/>
                    </svg>
                    <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
                  </div>
                  <button v-if="sceneImages[angle.key]" class="slot-remove" type="button"
                          @click.stop="clearSceneImage(angle.key)">×
                  </button>
                  <div v-if="uploading && uploadSlot === angle.key" class="slot-busy-mask">上传中...</div>
                </div>
                <span class="text-[11px] text-[#8a94aa]">{{ angle.label }}</span>
              </div>
            </div>
          </section>
          <section>
            <div class="flex items-center justify-between"><h3 class="m-0 text-[13px] font-medium text-[#20283b]">
              不同光照</h3>
              <n-button class="!rounded-[8px]" color="#f0820d" secondary size="small" @click="openImageGeneration(['morning', 'afternoon', 'night'])">
                生成不同光照
              </n-button>
            </div>
            <div class="mt-3 flex flex-wrap gap-5">
              <div v-for="light in sceneLightSlots" :key="light.key" class="flex flex-col items-center gap-1.5">
                <div :class="{ 'is-busy': uploading && uploadSlot === light.key }" class="image-slot image-slot--scene"
                     @click="triggerImageUpload(light.key)">
                  <img v-if="sceneImages[light.key]?.url" :src="sceneImages[light.key]?.url || ''"
                       alt="" class="h-full w-full object-cover">
                  <div v-else class="flex h-full w-full flex-col items-center justify-center gap-1.5 text-[#c3c9d6]">
                    <svg fill="none" height="24" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.6"
                         viewBox="0 0 24 24" width="24">
                      <path d="M12 21s-7-5.5-7-11a7 7 0 0 1 14 0c0 5.5-7 11-7 11Z"/>
                      <circle cx="12" cy="10" r="2.6"/>
                    </svg>
                    <span class="px-1 text-center text-[10px] leading-3">点击上传或选择</span>
                  </div>
                  <button v-if="sceneImages[light.key]" class="slot-remove" type="button"
                          @click.stop="clearSceneImage(light.key)">×
                  </button>
                  <div v-if="uploading && uploadSlot === light.key" class="slot-busy-mask">上传中...</div>
                </div>
                <span class="text-[11px] text-[#8a94aa]">{{ light.label }}</span>
              </div>
            </div>
          </section>
        </div>
      </n-modal>
      <n-modal v-model:show="propVisible" :mask-closable="false" class="w-[min(92vw,560px)]" preset="card">
        <template #header>
          <div class="flex min-w-0 items-baseline gap-2 text-[16px] font-medium"><span
              class="truncate">道具详情: {{ propName }}</span>
            <template v-if="propForm.identity_title"><span class="text-[#c3c9d6]">|</span><span
                class="shrink-0 text-[13px] font-normal text-[#8a94aa]">{{ propForm.identity_title }}</span></template>
          </div>
        </template>
        <template #header-extra>
          <div class="flex items-center gap-2">
            <n-button :loading="propSaving" class="!rounded-[8px]" color="#3f4657" size="small" type="primary"
                      @click="savePropProfile">保存
            </n-button>
            <n-button class="!rounded-[8px]" size="small" @click="openImageGeneration(['concept'])">去生图</n-button>
            <n-button class="!rounded-[8px]" color="#f0820d" ghost size="small" @click="notifyGenerationPending">训练
            </n-button>
          </div>
        </template>
        <div v-if="propLoading" class="py-16 text-center text-sm text-[#929bad]">正在加载道具详情...</div>
        <div v-else class="space-y-5">
          <section>
            <div class="flex items-center gap-1.5">
              <svg fill="none" height="14" stroke="#8a94aa" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.8"
                   viewBox="0 0 24 24" width="14">
                <rect height="16" rx="3" width="16" x="4" y="4"/>
              </svg>
              <n-input v-model:value="propForm.category" :bordered="false" class="!w-[180px] !px-0 text-[13px] font-medium"
                       placeholder="道具类别，如：武器"/>
            </div>
            <n-input v-model:value="propForm.description" :autosize="{ minRows: 3, maxRows: 10 }" :bordered="false"
                     class="mt-1 !px-0" placeholder="填写道具外观、材质与状态描述，供图像生成参考" type="textarea"/>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#8a94aa]">剧情作用</h3>
            <n-input v-model:value="propForm.plot_function" :autosize="{ minRows: 2, maxRows: 8 }" :bordered="false"
                     class="mt-1 !px-0" placeholder="填写道具在剧情中的作用与出场要求" type="textarea"/>
          </section>
          <section>
            <h3 class="m-0 text-[13px] font-medium text-[#8a94aa]">道具概念图</h3>
            <div class="mt-3 flex justify-center">
              <div :class="{ 'is-busy': uploading && uploadSlot === 'concept' }" class="image-slot image-slot--concept"
                   @click="triggerImageUpload('concept')">
                <img v-if="propImages.concept?.url" :src="propImages.concept.url" alt=""
                     class="h-full w-full object-cover">
                <div v-else class="flex h-full w-full flex-col items-center justify-center gap-2 text-[#c3c9d6]">
                  <svg fill="none" height="34" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5"
                       viewBox="0 0 24 24" width="34">
                    <path d="M12 2.8 20.5 7.4v9.2L12 21.2 3.5 16.6V7.4L12 2.8Z"/>
                    <path d="M3.7 7.5 12 12l8.3-4.5"/>
                    <path d="M12 12v9"/>
                  </svg>
                  <span class="text-[11px]">点击上传或选择</span>
                </div>
                <button v-if="propImages.concept" class="slot-remove" type="button" @click.stop="clearPropImage">×
                </button>
                <div v-if="uploading && uploadSlot === 'concept'" class="slot-busy-mask">上传中...</div>
              </div>
            </div>
          </section>
        </div>
      </n-modal>
      <input ref="fileInputRef" accept="image/*" class="hidden" type="file" @change="onImageChosen">
    </div>
  </main>
</template>

<script lang="ts" setup>
import {computed, onMounted, reactive, ref, watch} from 'vue'

interface AssetItem {
  id: number;
  name: string;
  type: string;
  description: string | null;
  reference_url: string | null;
  notes: string | null;
  status: string;
  updated_at: string | null
}

interface AssetResponse {
  list: AssetItem[];
  completeness: number
}

interface ProviderConfig {
  id: number;
  provider: string;
  api_type?: string;
  model_id: string;
  base_url: string;
  enabled: boolean
}

interface ModelOption {
  label: string;
  value: string
}

interface GeneratedImageFile {
  id: string;
  file_name: string;
  content_type: string | null;
  signed_download_url: string;
  download_expires_at: string
}

interface GeneratedImageResponse {
  list: GeneratedImageFile[]
}

interface ExtractionError {
  message: string;
  error_code?: number | null;
  response_content?: string | null;
  provider?: string;
  request_id?: string
}

interface ExtractionJob {
  type: string;
  label: string;
  agentLabel: string;
  description: string;
  enabled: boolean;
  modelKey: string
}

interface CharacterImageRef {
  file_id: string | null;
  url: string | null
}

interface CharacterProfileData {
  asset_id: number;
  name: string;
  description: string | null;
  reference_url: string | null;
  status: string;
  identity_title: string | null;
  role_tag: string | null;
  gender: string | null;
  age_stage: string | null;
  age_note: string | null;
  is_real_person: boolean
  face_description: string | null;
  costume_description: string | null
  three_view_front: CharacterImageRef | null;
  three_view_side: CharacterImageRef | null;
  three_view_back: CharacterImageRef | null;
  three_view_merged: CharacterImageRef | null;
  expressions: CharacterImageRef[]
  hairstyle: string | null;
  costume_ref: string | null;
  accessory_ref: string | null;
  voice_asset_id: number | null;
  voice_asset_name: string | null
}

interface SceneProfileData {
  asset_id: number;
  name: string;
  identity_title: string | null;
  location: string | null;
  time: string | null;
  atmosphere: string | null;
  description: string | null
  main_image: CharacterImageRef | null
  view_east: CharacterImageRef | null;
  view_south: CharacterImageRef | null;
  view_west: CharacterImageRef | null;
  view_north: CharacterImageRef | null
  angle_long_shot: CharacterImageRef | null;
  angle_full_shot: CharacterImageRef | null;
  angle_medium_shot: CharacterImageRef | null;
  angle_close_shot: CharacterImageRef | null
  light_morning: CharacterImageRef | null;
  light_afternoon: CharacterImageRef | null;
  light_night: CharacterImageRef | null
}

interface PropProfileData {
  asset_id: number;
  name: string;
  identity_title: string | null;
  category: string | null;
  plot_function: string | null;
  description: string | null
  concept_image: CharacterImageRef | null
}

type CharacterImageSlot = 'front' | 'side' | 'back' | 'merged'
type SceneImageSlot =
    'main'
    | 'east'
    | 'south'
    | 'west'
    | 'north'
    | 'longShot'
    | 'fullShot'
    | 'mediumShot'
    | 'closeShot'
    | 'morning'
    | 'afternoon'
    | 'night'
type PropImageSlot = 'concept'
type ProfileImageSlot = CharacterImageSlot | SceneImageSlot | PropImageSlot
type GenerationSlot = ProfileImageSlot | 'expression'
const message = useMessage();
const http = useHttp();
const route = useRoute();
const {activeProject, loadProjects} = useProjectWorkspace();
const {activeEpisode, loadEpisodes, loadLibrary} = useEpisodeWorkspace()
const assets = ref<AssetItem[]>([]);
const loading = ref(false);
const saving = ref(false);
const completeness = ref(0);
const search = ref('');
const selectedFilter = ref('ALL');
const editorVisible = ref(false);
const editing = ref<AssetItem | null>(null)
const extractionVisible = ref(false);
const extracting = ref(false);
const extractionError = ref<ExtractionError | null>(null);
const providers = ref<ProviderConfig[]>([]);
const modelOptions = ref<ModelOption[]>([])
const imageGenerationVisible = ref(false)
const imageGenerationModelKey = ref('')
const imageGenerationSlots = ref<GenerationSlot[]>([])
const imageGenerationMode = ref<'generation' | 'edit'>('generation')
const imageGenerationModeOptions = [
  {label: '文生图', value: 'generation'},
  {label: '图生图', value: 'edit'}
]
const generationSlotLabel: Record<GenerationSlot, string> = {
  front: '正面视图',
  side: '侧面视图',
  back: '背面视图',
  merged: '三视图合成',
  expression: '表情参考图',
  main: '主视觉',
  east: '东面视图',
  south: '南面视图',
  west: '西面视图',
  north: '北面视图',
  longShot: '远景',
  fullShot: '全景',
  mediumShot: '中景',
  closeShot: '近景',
  morning: '晨光',
  afternoon: '午后光照',
  night: '夜晚光照',
  concept: '道具概念图'
}
const imageGenerating = ref(false)
const extractionJobs = reactive<ExtractionJob[]>([
  {
    type: 'SCENE_BACKGROUND',
    label: '场景提取智能体',
    agentLabel: '场景/背景智能体',
    description: '提取剧本中的场景、地点、时间、光线和视觉氛围。',
    enabled: true,
    modelKey: ''
  },
  {
    type: 'CHARACTER',
    label: '角色提取智能体',
    agentLabel: '角色/发型智能体',
    description: '提取人物身份、外貌、性格、关系和出场要求。',
    enabled: true,
    modelKey: ''
  },
  {
    type: 'PROP',
    label: '道具提取智能体',
    agentLabel: '道具/物件智能体',
    description: '提取对剧情有推动作用的关键道具与重要物件。',
    enabled: true,
    modelKey: ''
  },
  {
    type: 'COSTUME',
    label: '服装提取智能体',
    agentLabel: '服装/造型智能体',
    description: '提取人物服装、配饰、色彩和连续性要求。',
    enabled: false,
    modelKey: ''
  },
])
const draft = reactive({name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT'})
const characterVisible = ref(false);
const characterLoading = ref(false);
const characterSaving = ref(false);
const characterAssetId = ref(0)
const enrichmentVisible = ref(false)
const enrichmentLoading = ref(false)
const enrichmentModelKey = ref('')
const enrichmentTask = ref<{id: number; status: string; result_status?: string; result?: unknown} | null>(null)
const characterSavedFingerprint = ref('')
const characterForm = reactive({
  name: '',
  description: '',
  reference_url: '',
  status: 'DRAFT',
  identity_title: '',
  role_tag: '',
  gender: '',
  age_stage: '',
  age_note: '',
  is_real_person: false,
  face_description: '',
  costume_description: '',
  hairstyle: '',
  costume_ref: '',
  accessory_ref: '',
  voice_asset_id: 0
})
const characterImages = reactive<{
  front: CharacterImageRef | null;
  side: CharacterImageRef | null;
  back: CharacterImageRef | null;
  merged: CharacterImageRef | null;
  expressions: CharacterImageRef[]
}>({front: null, side: null, back: null, merged: null, expressions: []})
const characterDirty = computed(() => characterSavedFingerprint.value !== characterFingerprint())
const uploadSlot = ref<ProfileImageSlot | ''>('');
const uploading = ref(false);
const fileInputRef = ref<HTMLInputElement | null>(null)
const maxImageUploadBytes = 20 * 1024 * 1024
const threeViewSlots: { key: CharacterImageSlot; label: string }[] = [{key: 'front', label: '正面'}, {
  key: 'side',
  label: '侧面'
}, {key: 'back', label: '背面'}]
const sceneVisible = ref(false);
const sceneLoading = ref(false);
const sceneSaving = ref(false);
const sceneAssetId = ref(0)
const sceneForm = reactive({identity_title: '', location: '', time: '', atmosphere: '', description: ''})
const sceneImages = reactive<Record<SceneImageSlot, CharacterImageRef | null>>({
  main: null,
  east: null,
  south: null,
  west: null,
  north: null,
  longShot: null,
  fullShot: null,
  mediumShot: null,
  closeShot: null,
  morning: null,
  afternoon: null,
  night: null
})
const sceneViewSlots: { key: SceneImageSlot; label: string }[] = [{key: 'east', label: '东面'}, {
  key: 'south',
  label: '南面'
}, {key: 'west', label: '西面'}, {key: 'north', label: '北面'}]
const sceneAngleSlots: { key: SceneImageSlot; label: string }[] = [{key: 'longShot', label: '远景'}, {
  key: 'fullShot',
  label: '全景'
}, {key: 'mediumShot', label: '中景'}, {key: 'closeShot', label: '近景'}]
const sceneLightSlots: { key: SceneImageSlot; label: string }[] = [{key: 'morning', label: '晨光'}, {
  key: 'afternoon',
  label: '午后'
}, {key: 'night', label: '夜晚'}]
const propVisible = ref(false);
const propLoading = ref(false);
const propSaving = ref(false);
const propAssetId = ref(0)
const propForm = reactive({identity_title: '', category: '', plot_function: '', description: ''})
const propImages = reactive<Record<PropImageSlot, CharacterImageRef | null>>({concept: null})
const elementFields = [{key: 'hairstyle', label: '发型'}, {key: 'costume_ref', label: '服饰'}, {
  key: 'accessory_ref',
  label: '配饰'
}] as const
const voiceOptions = computed(() => [{
  label: '未绑定',
  value: 0
}, ...assets.value.filter((item) => item.type === 'VOICE').map((item) => ({label: item.name, value: item.id}))])
const characterName = computed(() => assets.value.find((item) => item.id === characterAssetId.value)?.name || '')
const sceneName = computed(() => assets.value.find((item) => item.id === sceneAssetId.value)?.name || '')
const propName = computed(() => assets.value.find((item) => item.id === propAssetId.value)?.name || '')
const voiceBindingLabel = computed(() => characterForm.voice_asset_id ? (voiceOptions.value.find((option) => option.value === characterForm.voice_asset_id)?.label || '未绑定声线') : '未绑定声线')
const filters = [{key: 'ALL', label: '全部'}, {key: 'CHARACTER', label: '角色'}, {
  key: 'SCENE_BACKGROUND',
  label: '场景背景'
}, {key: 'SCENE', label: '场景'}, {key: 'PROP', label: '道具'}, {key: 'COSTUME', label: '服饰'}, {
  key: 'VOICE',
  label: '声音'
}]
const typeLabel: Record<string, string> = {
  CHARACTER: '角色',
  SCENE_BACKGROUND: '场景背景',
  SCENE: '场景',
  PROP: '道具',
  COSTUME: '服饰',
  VOICE: '声音',
  MUSIC: '音乐',
  SOUND_EFFECT: '音效'
}
const statusLabel: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_CONFIRMATION: '待确认',
  LOCKED: '已锁定',
  NEEDS_COPYRIGHT: '缺少版权',
  TO_GENERATE: '待生成',
  READY: '已就绪',
  ARCHIVED: '已归档'
}
const colorByType: Record<string, string> = {
  CHARACTER: 'linear-gradient(135deg,#9a8bea,#6556d9)',
  SCENE_BACKGROUND: 'linear-gradient(135deg,#eda977,#d87947)',
  PROP: 'linear-gradient(135deg,#c9a66f,#a8783d)',
  COSTUME: 'linear-gradient(135deg,#de8da8,#b74c72)',
  VOICE: 'linear-gradient(135deg,#66bfa7,#278d76)',
  MUSIC: 'linear-gradient(135deg,#ae8bd4,#7852ad)',
  SOUND_EFFECT: 'linear-gradient(135deg,#79aee4,#447ab7)'
}
const filteredAssets = computed(() => assets.value.filter((item) => (selectedFilter.value === 'ALL' || item.type === selectedFilter.value) && (!search.value || `${item.name}${item.description || ''}`.toLowerCase().includes(search.value.toLowerCase()))))
const assistantMessage = ref('我是资产统筹助手。可以帮助检查本集资产缺口和连续性。');
const assistantSuggestions = ['检查本集缺口', '完善角色一致性', '整理服饰清单']
const projectId = computed(() => Number(route.params.projectId || activeProject.value?.id));
const routeEpisodeId = computed(() => Number(route.params.episodeId || ''));
const episodeId = computed(() => {
  if (Number.isInteger(routeEpisodeId.value) && routeEpisodeId.value > 0) return routeEpisodeId.value;
  if (activeEpisode.value?.projectId !== String(projectId.value)) return Number.NaN;
  return Number(activeEpisode.value.id)
});
const hasEpisode = computed(() => Number.isInteger(episodeId.value) && episodeId.value > 0);
const episodePath = computed(() => hasEpisode.value ? `/projects/${projectId.value}/episodes/${episodeId.value}/assets` : `/projects/${projectId.value}/assets`)
const typeOptions = computed(() => (hasEpisode.value ? filters.filter((item) => ['CHARACTER', 'SCENE_BACKGROUND', 'PROP', 'COSTUME', 'VOICE'].includes(item.key)) : filters.filter((item) => ['CHARACTER', 'SCENE', 'PROP', 'VOICE'].includes(item.key))).map((item) => ({
  label: item.label,
  value: item.key
})).concat([{label: '音乐', value: 'MUSIC'}, {label: '音效', value: 'SOUND_EFFECT'}]))
const statusOptions = Object.entries(statusLabel).map(([value, label]) => ({value, label}))
const selectedExtractionCount = computed(() => extractionJobs.filter((job) => job.enabled).length)
const imageProviders = computed(() => providers.value.filter((item) => (item.api_type || 'CHAT') === 'IMAGE'))
const imageModelOptions = computed<ModelOption[]>(() => imageProviders.value
    .filter((provider) => provider.model_id?.trim())
    .map((provider) => ({label: `${provider.provider} · ${provider.model_id}`, value: `${provider.id}::${provider.model_id}`})))

async function loadAssets() {
  if (!Number.isInteger(projectId.value) || projectId.value <= 0) return;
  loading.value = true;
  try {
    const data = await http.get<AssetResponse>(episodePath.value);
    assets.value = data.list || [];
    completeness.value = data.completeness || 0;
    try {
      await loadLibrary(projectId.value)
    } catch { /* Keep the workflow list usable when the sidebar refresh is unavailable. */
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '资产加载失败')
  } finally {
    loading.value = false
  }
}

async function loadProviders() {
  try {
    const response = await http.get<{ list: ProviderConfig[] }>('/ai/model-providers')
    providers.value = response.list.filter((item) => item.enabled)
    const options: ModelOption[] = []
    const chatProviders = providers.value.filter((provider) => (provider.api_type || 'CHAT') === 'CHAT')
    for (const provider of chatProviders) {
      if (provider.model_id) options.push({
        label: `${provider.provider} · ${provider.model_id}`,
        value: `${provider.id}::${provider.model_id}`
      })
      try {
        const formData = new FormData();
        formData.append('base_url', provider.base_url);
        formData.append('config_id', String(provider.id))
        const models = await http.post<{ list: { id: string }[] }, FormData>('/ai/model-providers/models', formData)
        models.list.forEach((model) => {
          const value = `${provider.id}::${model.id}`;
          if (!options.some((option) => option.value === value)) options.push({
            label: `${provider.provider} · ${model.id}`,
            value
          })
        })
      } catch { /* Keep the configured default model when model discovery is unavailable. */
      }
    }
    modelOptions.value = options
    extractionJobs.forEach((job) => {
      if (!job.modelKey) job.modelKey = options[0]?.value || ''
    })
  } catch {
    providers.value = [];
    modelOptions.value = []
  }
}

function openExtraction() {
  extractionError.value = null;
  extractionJobs.forEach((job) => {
    if (!job.modelKey) job.modelKey = modelOptions.value[0]?.value || ''
  });
  extractionVisible.value = true
}

function openCreate() {
  editing.value = null;
  Object.assign(draft, {name: '', type: 'PROP', description: '', reference_url: '', notes: '', status: 'DRAFT'});
  editorVisible.value = true
}

function openEdit(asset: AssetItem) {
  if (hasEpisode.value && asset.type === 'CHARACTER') {
    openCharacterDetail(asset);
    return
  }
  if (hasEpisode.value && asset.type === 'SCENE_BACKGROUND') {
    openSceneDetail(asset);
    return
  }
  if (hasEpisode.value && asset.type === 'PROP') {
    openPropDetail(asset);
    return
  }
  editing.value = asset;
  Object.assign(draft, {
    name: asset.name,
    type: asset.type,
    description: asset.description || '',
    reference_url: asset.reference_url || '',
    notes: asset.notes || '',
    status: asset.status
  });
  editorVisible.value = true
}

async function submitAsset() {
  if (!draft.name.trim()) {
    message.warning('请填写资产名称');
    return
  }
  saving.value = true;
  try {
    const payload = {
      name: draft.name.trim(),
      type: draft.type,
      description: draft.description,
      reference_url: draft.reference_url || '',
      notes: draft.notes || '',
      status: draft.status
    };
    if (editing.value) await http.put(`${episodePath.value}/${editing.value.id}`, payload); else await http.post(episodePath.value, payload);
    editorVisible.value = false;
    await loadAssets();
    message.success('资产已保存')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '资产保存失败')
  } finally {
    saving.value = false
  }
}

async function removeAsset(asset: AssetItem) {
  try {
    await http.delete(`${episodePath.value}/${asset.id}`);
    await loadAssets();
    message.success('资产已删除')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '资产删除失败')
  }
}

async function startExtraction() {
  const jobs = extractionJobs.filter((job) => job.enabled)
  if (!jobs.length) {
    message.warning('请至少选择一种资产类型');
    return
  }
  if (jobs.some((job) => !job.modelKey)) {
    message.warning('请为每种资产选择模型');
    return
  }
  extracting.value = true;
  extractionError.value = null
  try {
    for (const job of jobs) {
      const [providerConfigId, model] = job.modelKey.split('::')
      try {
        await http.post(`${episodePath.value}/extract`, {
          type: job.type,
          provider_config_id: Number(providerConfigId),
          model
        })
      } catch (error) {
        extractionError.value = extractExtractionError(error, '资产提取失败')
        throw error
      }
    }
    extractionVisible.value = false
    await loadAssets()
    message.success('资产提取完成')
  } catch (error) {
    if (!extractionError.value) message.error(error instanceof Error ? error.message : '资产提取失败')
  } finally {
    extracting.value = false
  }
}

function extractExtractionError(error: unknown, fallback: string): ExtractionError {
  const value = error as { message?: string; data?: { data?: { error?: ExtractionError }; message?: string } }
  const structured = value.data?.data?.error
  return structured ? {
    ...structured,
    message: value.data?.message || value.message || fallback
  } : {message: value.data?.message || value.message || fallback}
}

async function saveDraft() {
  saving.value = true;
  try {
    await loadAssets();
    message.success('资产草稿已保存')
  } finally {
    saving.value = false
  }
}

async function openCharacterDetail(asset: AssetItem) {
  characterAssetId.value = asset.id;
  characterVisible.value = true;
  characterLoading.value = true
  try {
    const data = await http.get<CharacterProfileData>(`${episodePath.value}/${asset.id}/character-profile`)
    applyCharacterProfile(data)
  } catch (error) {
    characterVisible.value = false
    message.error(error instanceof Error ? error.message : '角色详情加载失败')
  } finally {
    characterLoading.value = false
  }
}

function applyCharacterProfile(data: CharacterProfileData) {
  Object.assign(characterForm, {
    name: data.name || '',
    description: data.description || '',
    reference_url: data.reference_url || '',
    status: data.status || 'DRAFT',
    identity_title: data.identity_title || '',
    role_tag: data.role_tag || '',
    gender: data.gender || '',
    age_stage: data.age_stage || '',
    age_note: data.age_note || '',
    is_real_person: !!data.is_real_person,
    face_description: data.face_description || '',
    costume_description: data.costume_description || '',
    hairstyle: data.hairstyle || '',
    costume_ref: data.costume_ref || '',
    accessory_ref: data.accessory_ref || '',
    voice_asset_id: data.voice_asset_id || 0
  })
  characterImages.front = data.three_view_front;
  characterImages.side = data.three_view_side;
  characterImages.back = data.three_view_back;
  characterImages.merged = data.three_view_merged;
  characterImages.expressions = data.expressions || []
  characterSavedFingerprint.value = characterFingerprint()
}

function characterFingerprint(): string {
  return JSON.stringify({
    form: {...characterForm},
    images: {
      front: characterImages.front?.file_id || null,
      side: characterImages.side?.file_id || null,
      back: characterImages.back?.file_id || null,
      merged: characterImages.merged?.file_id || null,
      expressions: characterImages.expressions.map((item) => item.file_id || null),
    },
  })
}

function closeCharacterDetail() {
  if (characterDirty.value && !window.confirm('角色详情有未保存修改，确定关闭吗？')) return
  characterVisible.value = false
}

function handleCharacterVisibility(show: boolean) {
  if (show) {
    characterVisible.value = true
    return
  }
  closeCharacterDetail()
}

async function saveCharacterProfile(): Promise<boolean> {
  if (!characterAssetId.value) return false
  if (!characterForm.name.trim()) {
    message.warning('请填写角色名称')
    return false
  }
  characterSaving.value = true
  try {
    const payload = {
      name: characterForm.name,
      description: characterForm.description,
      reference_url: characterForm.reference_url,
      status: characterForm.status,
      identity_title: characterForm.identity_title,
      role_tag: characterForm.role_tag,
      gender: characterForm.gender,
      age_stage: characterForm.age_stage,
      age_note: characterForm.age_note,
      is_real_person: characterForm.is_real_person,
      face_description: characterForm.face_description,
      costume_description: characterForm.costume_description,
      three_view_front_file_id: characterImages.front?.file_id || '',
      three_view_side_file_id: characterImages.side?.file_id || '',
      three_view_back_file_id: characterImages.back?.file_id || '',
      three_view_merged_file_id: characterImages.merged?.file_id || '',
      expression_file_ids: characterImages.expressions.map((item) => item.file_id || '').filter(Boolean),
      hairstyle: characterForm.hairstyle,
      costume_ref: characterForm.costume_ref,
      accessory_ref: characterForm.accessory_ref,
      voice_asset_id: characterForm.voice_asset_id || null
    }
    const data = await http.put<CharacterProfileData, Record<string, unknown>>(`${episodePath.value}/${characterAssetId.value}/character-profile`, payload, {payloadMode: 'json'})
    applyCharacterProfile(data)
    const target = assets.value.find((item) => item.id === characterAssetId.value)
    if (target) {
      target.name = data.name
      target.description = data.description || null
      target.reference_url = data.reference_url || null
      target.status = data.status
    }
    message.success('角色详情已保存')
    return true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '角色详情保存失败')
    return false
  } finally {
    characterSaving.value = false
  }
}

async function openCharacterEnrichment() {
  if (!hasEpisode.value || !characterAssetId.value) {
    message.warning('请先选择具体剧集和角色')
    return
  }
  if (!modelOptions.value.length) {
    message.warning('请先配置启用的对话模型')
    return
  }
  if (characterDirty.value && !await saveCharacterProfile()) return
  enrichmentModelKey.value = enrichmentModelKey.value || modelOptions.value[0].value
  enrichmentTask.value = null
  enrichmentVisible.value = true
}

async function startCharacterEnrichment() {
  const [providerConfigId, model] = enrichmentModelKey.value.split('::')
  if (!providerConfigId || !model || !hasEpisode.value || !characterAssetId.value) {
    message.warning('请选择有效的对话模型')
    return
  }
  enrichmentLoading.value = true
  const idempotencyKey = `character-enrichment-${projectId.value}-${episodeId.value}-${characterAssetId.value}-${Date.now()}`
  try {
    const response = await http.post<{task_id: string; status: string}>(
      `/projects/${projectId.value}/episodes/${episodeId.value}/assets/${characterAssetId.value}/character-enrichment-tasks`,
      {provider_config_id: Number(providerConfigId), model},
      {headers: {'Idempotency-Key': idempotencyKey}},
    )
    const taskId = Number(response.task_id)
    if (!Number.isInteger(taskId) || taskId <= 0) throw new Error('角色扩写任务创建失败')
    await pollCharacterEnrichment(taskId)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '角色扩写任务创建失败')
  } finally {
    enrichmentLoading.value = false
  }
}

async function pollCharacterEnrichment(taskId: number) {
  const path = `/projects/${projectId.value}/episodes/${episodeId.value}/character-enrichment-tasks/${taskId}`
  for (let attempt = 0; attempt < 90; attempt += 1) {
    const current = await http.get<{task_status: string; result_status: string; result?: unknown}>(path)
    enrichmentTask.value = {id: taskId, status: current.task_status, result_status: current.result_status, result: current.result}
    if (['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(current.task_status)) {
      if (current.task_status === 'SUCCEEDED' && current.result_status === 'STAGED') message.success('角色卡草稿已生成')
      return
    }
    await new Promise((resolve) => window.setTimeout(resolve, 1000))
  }
  throw new Error('角色扩写任务等待超时，请到任务中心查看')
}

async function adoptCharacterEnrichment() {
  const task = enrichmentTask.value
  if (!task || task.result_status !== 'STAGED') return
  enrichmentLoading.value = true
  try {
    await http.post(`/projects/${projectId.value}/episodes/${episodeId.value}/character-enrichment-tasks/${task.id}/adopt`, undefined, {headers: {'Idempotency-Key': `adopt-character-${task.id}`}})
    message.success('角色卡已采纳')
    enrichmentVisible.value = false
    await loadAssets()
    if (characterAssetId.value) await openCharacterDetail(assets.value.find((item) => item.id === characterAssetId.value) || {id: characterAssetId.value, name: characterName.value, type: 'CHARACTER', description: null, reference_url: null, notes: null, status: 'DRAFT', updated_at: null})
  } catch (error) {
    message.error(error instanceof Error ? error.message : '角色卡采纳失败')
  } finally {
    enrichmentLoading.value = false
  }
}

function triggerImageUpload(slot: ProfileImageSlot) {
  uploadSlot.value = slot;
  const input = fileInputRef.value;
  if (input) {
    input.value = '';
    input.click()
  }
}

async function onImageChosen(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  const slot = uploadSlot.value
  if (!file || !slot) return
  if (!file.type.startsWith('image/')) {
    message.warning('请选择图片文件')
    return
  }
  if (file.size > maxImageUploadBytes) {
    message.warning('图片大小不能超过 20MB')
    return
  }
  uploading.value = true
  try {
    const formData = new FormData();
    formData.append('files', file)
    const response = await http.post<{
      list: { id: string; signed_download_url: string }[]
    }, FormData>('/files', formData)
    const uploaded = response.list?.[0]
    if (uploaded) {
      const image = {file_id: uploaded.id, url: uploaded.signed_download_url};
      if (isSceneSlot(slot)) sceneImages[slot] = image; else if (slot === 'concept') propImages.concept = image; else characterImages[slot] = image
    }
  } catch (error) {
    message.error(error instanceof Error ? error.message : '图片上传失败')
  } finally {
    uploading.value = false;
    uploadSlot.value = ''
  }
}

function isSceneSlot(slot: ProfileImageSlot): slot is SceneImageSlot {
  return slot in sceneImages
}

function clearCharacterImage(slot: CharacterImageSlot) {
  if (!characterImages[slot] || window.confirm('确定移除这张角色视图吗？')) characterImages[slot] = null
}

function clearExpressionImage(index: number) {
  if (window.confirm('确定移除这张表情参考图吗？')) characterImages.expressions.splice(index, 1)
}

function clearSceneImage(slot: SceneImageSlot) {
  sceneImages[slot] = null
}

function clearPropImage() {
  propImages.concept = null
}

async function openPropDetail(asset: AssetItem) {
  propAssetId.value = asset.id;
  propVisible.value = true;
  propLoading.value = true
  try {
    const data = await http.get<PropProfileData>(`${episodePath.value}/${asset.id}/prop-profile`)
    applyPropProfile(data)
  } catch (error) {
    propVisible.value = false
    message.error(error instanceof Error ? error.message : '道具详情加载失败')
  } finally {
    propLoading.value = false
  }
}

function applyPropProfile(data: PropProfileData) {
  Object.assign(propForm, {
    identity_title: data.identity_title || '',
    category: data.category || '',
    plot_function: data.plot_function || '',
    description: data.description || ''
  })
  propImages.concept = data.concept_image
}

async function savePropProfile(): Promise<boolean> {
  if (!propAssetId.value) return false
  propSaving.value = true
  try {
    const payload = {
      identity_title: propForm.identity_title,
      category: propForm.category,
      plot_function: propForm.plot_function,
      description: propForm.description,
      concept_image_file_id: propImages.concept?.file_id || ''
    }
    const data = await http.put<PropProfileData, Record<string, unknown>>(`${episodePath.value}/${propAssetId.value}/prop-profile`, payload, {payloadMode: 'json'})
    applyPropProfile(data)
    const target = assets.value.find((item) => item.id === propAssetId.value);
    if (target) target.description = data.description || null
    message.success('道具详情已保存')
    return true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '道具详情保存失败')
    return false
  } finally {
    propSaving.value = false
  }
}

async function openSceneDetail(asset: AssetItem) {
  sceneAssetId.value = asset.id;
  sceneVisible.value = true;
  sceneLoading.value = true
  try {
    const data = await http.get<SceneProfileData>(`${episodePath.value}/${asset.id}/scene-profile`)
    applySceneProfile(data)
  } catch (error) {
    sceneVisible.value = false
    message.error(error instanceof Error ? error.message : '场景详情加载失败')
  } finally {
    sceneLoading.value = false
  }
}

function applySceneProfile(data: SceneProfileData) {
  Object.assign(sceneForm, {
    identity_title: data.identity_title || '',
    location: data.location || '',
    time: data.time || '',
    atmosphere: data.atmosphere || '',
    description: data.description || ''
  })
  sceneImages.main = data.main_image;
  sceneImages.east = data.view_east;
  sceneImages.south = data.view_south;
  sceneImages.west = data.view_west;
  sceneImages.north = data.view_north
  sceneImages.longShot = data.angle_long_shot;
  sceneImages.fullShot = data.angle_full_shot;
  sceneImages.mediumShot = data.angle_medium_shot;
  sceneImages.closeShot = data.angle_close_shot
  sceneImages.morning = data.light_morning;
  sceneImages.afternoon = data.light_afternoon;
  sceneImages.night = data.light_night
}

async function saveSceneProfile(): Promise<boolean> {
  if (!sceneAssetId.value) return false
  sceneSaving.value = true
  try {
    const payload = {
      identity_title: sceneForm.identity_title,
      location: sceneForm.location,
      time: sceneForm.time,
      atmosphere: sceneForm.atmosphere,
      description: sceneForm.description,
      main_image_file_id: sceneImages.main?.file_id || '',
      view_east_file_id: sceneImages.east?.file_id || '',
      view_south_file_id: sceneImages.south?.file_id || '',
      view_west_file_id: sceneImages.west?.file_id || '',
      view_north_file_id: sceneImages.north?.file_id || '',
      angle_long_shot_file_id: sceneImages.longShot?.file_id || '',
      angle_full_shot_file_id: sceneImages.fullShot?.file_id || '',
      angle_medium_shot_file_id: sceneImages.mediumShot?.file_id || '',
      angle_close_shot_file_id: sceneImages.closeShot?.file_id || '',
      light_morning_file_id: sceneImages.morning?.file_id || '',
      light_afternoon_file_id: sceneImages.afternoon?.file_id || '',
      light_night_file_id: sceneImages.night?.file_id || ''
    }
    const data = await http.put<SceneProfileData, Record<string, unknown>>(`${episodePath.value}/${sceneAssetId.value}/scene-profile`, payload, {payloadMode: 'json'})
    applySceneProfile(data)
    const target = assets.value.find((item) => item.id === sceneAssetId.value);
    if (target) target.description = data.description || null
    message.success('场景详情已保存')
    return true
  } catch (error) {
    message.error(error instanceof Error ? error.message : '场景详情保存失败')
    return false
  } finally {
    sceneSaving.value = false
  }
}

function notifyGenerationPending() {
  message.info('训练能力暂未接入')
}

async function openImageGeneration(slots: GenerationSlot[]) {
  if (!hasEpisode.value) {
    message.warning('请先选择具体剧集')
    return
  }
  if (!imageModelOptions.value.length) {
    message.warning('请先配置启用的图片模型')
    return
  }
  const profileSaved = characterVisible.value
    ? await saveCharacterProfile()
    : sceneVisible.value
      ? await saveSceneProfile()
      : propVisible.value
        ? await savePropProfile()
        : false
  if (!profileSaved) return
  imageGenerationSlots.value = slots
  imageGenerationModelKey.value = imageModelOptions.value[0].value
  imageGenerationMode.value = 'generation'
  imageGenerationVisible.value = true
}

async function confirmImageGeneration() {
  const [providerConfigId, model] = imageGenerationModelKey.value.split('::')
  const slots = imageGenerationSlots.value
  if (!providerConfigId || !model || !slots.length) {
    message.warning('请选择图片模型和生成目标')
    return
  }
  imageGenerating.value = true
  const generatedFiles: GeneratedImageFile[] = []
  try {
    const generatedImages: { slot: GenerationSlot; image: CharacterImageRef }[] = []
    for (const slot of slots) {
      const assetId = characterVisible.value ? characterAssetId.value : sceneVisible.value ? sceneAssetId.value : propAssetId.value
      const operation = imageGenerationMode.value === 'edit' ? 'edits' : 'generations'
      const response = await http.post<GeneratedImageResponse>(`${episodePath.value}/${assetId}/images/${operation}`, {
        provider_config_id: Number(providerConfigId),
        model,
        target: slot,
        count: 1,
        size: '2K'
      })
      const file = response.list?.[0]
      if (!file) throw new Error('图片模型未返回结果')
      generatedFiles.push(file)
      generatedImages.push({slot, image: {file_id: file.id, url: file.signed_download_url}})
    }
    if (characterVisible.value) {
      generatedImages.forEach(({slot, image}) => {
        if (slot === 'expression') characterImages.expressions.push(image)
        else if (slot in characterImages) characterImages[slot as CharacterImageSlot] = image
      })
      if (!await saveCharacterProfile()) throw new Error('图片已保存，但角色档案保存失败，请重试')
    } else if (sceneVisible.value) {
      generatedImages.forEach(({slot, image}) => {
        if (slot in sceneImages) sceneImages[slot as SceneImageSlot] = image
      })
      if (!await saveSceneProfile()) throw new Error('图片已保存，但场景档案保存失败，请重试')
    } else if (propVisible.value) {
      const image = generatedImages.find(({slot}) => slot === 'concept')?.image
      if (image) propImages.concept = image
      if (!await savePropProfile()) throw new Error('图片已保存，但道具档案保存失败，请重试')
    }
    imageGenerationVisible.value = false
    message.success(`已生成并保存 ${generatedImages.length} 张图片`)
  } catch (error) {
    await Promise.all(generatedFiles.map((file) => http.delete(`/files/${file.id}`).catch(() => undefined)))
    message.error(error instanceof Error ? error.message : '图片生成失败')
  } finally {
    imageGenerating.value = false
  }
}

function updateAssistant(value: string) {
  assistantMessage.value = `收到“${value}”，我会整理当前剧集的执行建议。`
}

onMounted(async () => {
  try {
    await loadProjects()
  } catch {
  }
  try {
    await loadEpisodes(String(projectId.value))
  } catch {
  }
  await Promise.all([loadAssets(), loadProviders()])
});
watch(() => [route.params.episodeId, activeProject.value?.id], async () => {
  if (activeProject.value?.id) {
    try {
      await loadEpisodes(String(activeProject.value.id))
    } catch {
    }
  }
  await loadAssets()
})
</script>
<style scoped>
@media (max-width: 640px) {
  .asset-panel :deep(.n-card__content) {
    padding-left: 14px !important;
    padding-right: 14px !important;
  }
}

.tag-input {
  border-radius: 999px !important;
}

.tag-input :deep(.n-input__input-el), .tag-input :deep(.n-input__placeholder) {
  text-align: center;
}

.tag-primary :deep(.n-input__input-el), .tag-primary :deep(.n-input__placeholder) {
  color: #3b6cff;
}

.plain-area {
  background: #f7f8fa !important;
  border-radius: 10px !important;
}

.image-slot {
  position: relative;
  width: 88px;
  height: 108px;
  flex-shrink: 0;
  overflow: hidden;
  border-radius: 10px;
  border: 1px dashed #d9dee8;
  background: #fafbfd;
  cursor: pointer;
}

.image-slot:hover {
  border-color: #f0a35e;
}

.image-slot:focus-visible {
  outline: 2px solid #6654ee;
  outline-offset: 3px;
}

.image-slot--wide {
  width: 196px;
}

.image-slot--scene {
  width: 116px;
  height: 78px;
}

.image-slot--hero {
  width: 100%;
  height: 264px;
}

.image-slot--concept {
  width: 264px;
  height: 248px;
}

.image-slot.is-busy {
  pointer-events: none;
}

.slot-remove {
  position: absolute;
  top: 4px;
  right: 6px;
  width: 18px;
  height: 18px;
  line-height: 16px;
  border-radius: 999px;
  background: rgba(31, 40, 59, 0.55);
  color: #fff;
  font-size: 13px;
  text-align: center;
  border: none;
  cursor: pointer;
}

.slot-busy-mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(255, 255, 255, 0.72);
  font-size: 11px;
  color: #e87517;
}
</style>
