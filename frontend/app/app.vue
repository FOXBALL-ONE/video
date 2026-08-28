<template>
  <NMessageProvider>
    <div class="min-h-screen bg-[#f5f6fa]">
      <div class="border-b border-[#e5e8ef] bg-white/90 px-4 py-2 backdrop-blur sm:px-6 lg:px-8">
        <div class="mx-auto flex min-h-[48px] max-w-[1177px] items-center justify-between gap-4">
          <div class="flex min-w-0 items-center gap-3">
            <NuxtLink to="/" class="hidden shrink-0 text-[11px] font-bold tracking-[0.8px] text-[#5c52ee] sm:block">VIDEO WORKSPACE</NuxtLink>
            <span class="hidden h-5 w-px bg-[#e5e8ef] sm:block" aria-hidden="true"></span>
            <ProjectSwitcher />
          </div>
          <nav class="flex items-center gap-1 text-xs text-[#8994a8]" aria-label="工作区导航">
            <NuxtLink to="/" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">项目总览</NuxtLink>
            <NuxtLink to="/projects" class="rounded-lg px-2.5 py-2 transition-colors hover:bg-[#f0eeff] hover:text-[#5d52ec]">项目管理</NuxtLink>
          </nav>
        </div>
      </div>
      <NuxtPage />
    </div>
  </NMessageProvider>
</template>
