// @flow

import { type DiskletFile, type DiskletFolder } from './legacy.js'

/**
 * Interprets a path as a series of folder lookups,
 * handling special components like `.` and `..`.
 */
function followPath(folder: DiskletFolder, parts: Array<string>) {
  let i = 0 // Read index
  let j = 0 // Write index

  // Shift down good elements, dropping bad ones:
  while (i < parts.length) {
    const part = parts[i++]
    if (part === '..') j--
    else if (part !== '.' && part !== '') parts[j++] = part

    if (j < 0) throw new Error('Path would escape folder')
  }

  // Navigate the folder:
  for (i = 0; i < j; ++i) {
    folder = folder.folder(parts[i])
  }
  return folder
}

/**
 * Navigates down to the file indicated by the path.
 */
export function locateFile(folder: DiskletFolder, path: string): DiskletFile {
  const parts = path.split('/')
  const filename = parts.pop()
  return followPath(folder, parts).file(filename)
}

/**
 * Navigates down to the sub-folder indicated by the path.
 */
export function locateFolder(
  folder: DiskletFolder,
  path: string
): DiskletFolder {
  const parts = path.split('/')
  return followPath(folder, parts)
}

type FileIterator = (
  file: DiskletFile,
  name: string,
  folder: DiskletFolder
) => any

type FolderIterator = (
  folder: DiskletFolder,
  name: string,
  folder: DiskletFolder
) => any

/**
 * Applies an async function to all the files in a folder.
 */
export function mapFiles(
  folder: DiskletFolder,
  f: FileIterator
): Promise<Array<any>> {
  return folder
    .listFiles()
    .then(names =>
      Promise.all(names.map(name => f(folder.file(name), name, folder)))
    )
}

/**
 * Applies an async function to all the sub-folders in a folder.
 */
export function mapFolders(
  folder: DiskletFolder,
  f: FolderIterator
): Promise<Array<any>> {
  return folder
    .listFolders()
    .then(names =>
      Promise.all(names.map(name => f(folder.folder(name), name, folder)))
    )
}

/**
 * Recursively applies an async function to all the files in a folder tree.
 * The file names are expanded into paths, and the result is a flat array.
 */
export function mapAllFiles(
  folder: DiskletFolder,
  f: FileIterator
): Promise<Array<any>> {
  function recurse(folder, f, prefix): Promise<Array<any>> {
    return Promise.all([
      mapFiles(folder, (file, name) => f(file, prefix + name, folder)),
      mapFolders(folder, (folder, name) =>
        recurse(folder, f, prefix + name + '/')
      )
    ]).then(([files, folders]) => files.concat(...folders))
  }

  return recurse(folder, f, '')
}
