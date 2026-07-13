// Maps a filename to a Material Design Icon + accent color, for a nicer Explorer.
import {
  mdiLanguageTypescript, mdiLanguageJavascript, mdiReact, mdiCodeJson, mdiLanguageHtml5,
  mdiLanguageCss3, mdiLanguageMarkdown, mdiLanguageJava, mdiLanguagePython, mdiLanguageGo,
  mdiLanguageRust, mdiLanguageKotlin, mdiLanguageCpp, mdiLanguageC, mdiLanguageCsharp,
  mdiLanguagePhp, mdiLanguageRuby, mdiXml, mdiFileDocumentOutline, mdiConsole, mdiDatabase,
  mdiCog, mdiImage, mdiGit,
} from '@mdi/js';

interface FileIcon { path: string; color: string }

const BY_EXT: Record<string, FileIcon> = {
  ts: { path: mdiLanguageTypescript, color: '#3178c6' },
  tsx: { path: mdiReact, color: '#61dafb' },
  js: { path: mdiLanguageJavascript, color: '#f7df1e' },
  jsx: { path: mdiReact, color: '#61dafb' },
  mjs: { path: mdiLanguageJavascript, color: '#f7df1e' },
  json: { path: mdiCodeJson, color: '#cbcb41' },
  html: { path: mdiLanguageHtml5, color: '#e34c26' },
  css: { path: mdiLanguageCss3, color: '#563d7c' },
  scss: { path: mdiLanguageCss3, color: '#c6538c' },
  md: { path: mdiLanguageMarkdown, color: '#8aa6c1' },
  java: { path: mdiLanguageJava, color: '#e76f00' },
  py: { path: mdiLanguagePython, color: '#3572A5' },
  go: { path: mdiLanguageGo, color: '#00add8' },
  rs: { path: mdiLanguageRust, color: '#dea584' },
  kt: { path: mdiLanguageKotlin, color: '#a97bff' },
  kts: { path: mdiLanguageKotlin, color: '#a97bff' },
  cpp: { path: mdiLanguageCpp, color: '#00599c' },
  hpp: { path: mdiLanguageCpp, color: '#00599c' },
  c: { path: mdiLanguageC, color: '#00599c' },
  h: { path: mdiLanguageC, color: '#00599c' },
  cs: { path: mdiLanguageCsharp, color: '#178600' },
  php: { path: mdiLanguagePhp, color: '#4F5D95' },
  rb: { path: mdiLanguageRuby, color: '#701516' },
  xml: { path: mdiXml, color: '#8aa6c1' },
  yaml: { path: mdiCog, color: '#cb171e' },
  yml: { path: mdiCog, color: '#cb171e' },
  sh: { path: mdiConsole, color: '#89e051' },
  sql: { path: mdiDatabase, color: '#e38c00' },
  png: { path: mdiImage, color: '#a074c4' },
  jpg: { path: mdiImage, color: '#a074c4' },
  svg: { path: mdiImage, color: '#ffb13b' },
  gif: { path: mdiImage, color: '#a074c4' },
};

const BY_NAME: Record<string, FileIcon> = {
  '.gitignore': { path: mdiGit, color: '#f14e32' },
  '.gitattributes': { path: mdiGit, color: '#f14e32' },
};

const DEFAULT: FileIcon = { path: mdiFileDocumentOutline, color: '#8a94a6' };

export function fileIcon(name: string): FileIcon {
  if (BY_NAME[name]) return BY_NAME[name];
  const ext = name.split('.').pop()?.toLowerCase() ?? '';
  return BY_EXT[ext] ?? DEFAULT;
}
