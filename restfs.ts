#!/usr/bin/env deno run --allow-net --allow-read --allow-write --allow-env
import { exists } from "jsr:@std/fs/exists";
import { serveFile } from "https://deno.land/std@0.177.0/http/file_server.ts";

const PASSWORD = Deno.env.get("PASSWORD") ?? "changeme";
const REALM = "C4 Editor";

function checkAuth(headers: Headers): boolean {
  const auth = headers.get("authorization") || "";
  if (!auth.startsWith("Basic ")) return false;
  const b64 = auth.split(" ")[1];
  const [user, pass] = atob(b64).split(":");
  return user === "admin" && pass === PASSWORD;
}

const editorFiles = {
  ".c4": new URL("./restfs-mermaid.html", import.meta.url),
  ".png": new URL("./restfs-mermaid.html", import.meta.url),
  ".svg": new URL("./restfs-mermaid.html", import.meta.url),
};

const fileAssociations: Record<string, string> = {
  ".html": "text/html",
  ".c4":   "text/plain",
  ".svg":  "image/svg+xml",
  ".png":  "image/png",
};

Deno.serve(async (req) => {
  const url = new URL(req.url);
  const pathname = url.pathname;
  const method = req.method;
  const headers = req.headers;
  const fsPath = `.${pathname}`;
  console.log("1",method, pathname, fsPath);

  // 1) Remote import via ?src
  if (method === 'GET' && url.searchParams.has('src')) {
    const remoteUrl = url.searchParams.get('src')!;
    try {
      const response = await fetch(remoteUrl);
      if (!response.ok) {
        return new Response(`Failed to fetch remote file: ${response.statusText}`, { status: 500 });
      }
      const content = await response.text();
      const filename = remoteUrl.split('/').pop() || 'downloaded.c4';
      await Deno.writeTextFile(filename, content);
      return new Response(null, {
        status: 302,
        headers: { 'Location': `/${filename}?edit` }
      });
    } catch (error: any) {
      return new Response(`Error downloading file: ${error.message}`, { status: 500 });
    }
  }
  console.log("2",method, pathname, fsPath);

  // 2) Directory listing
  const fileExists = await exists(fsPath);
  try {
    const stat = fileExists ? await Deno.stat(fsPath) : null;
    if (stat?.isDirectory) {
      const wantsJson = headers.get('accept')?.includes('application/json');
      const entries = [];
      for await (const entry of Deno.readDir(fsPath)) {
        const name = entry.name + (entry.isDirectory ? '/' : '');
        const variants = [];
        if (!entry.isDirectory && entry.name.endsWith('.c4')) {
          const base = entry.name.slice(0, -3);
          variants.push(`${base}.svg`, `${base}.png`);
        }
        entries.push({ name, isDirectory: entry.isDirectory, variants });
      }
      if (wantsJson) {
        return new Response(JSON.stringify(entries), {
          headers: { 'content-type': 'application/json' }
        });
      }
      // HTML fallback
      let list = '<ul>';
      for await (const entry of Deno.readDir(fsPath)) {
        const slash = entry.isDirectory ? '/' : '';
        const link = `${pathname}${pathname.endsWith('/') ? '' : '/'}${entry.name}${slash}`;
        list += `<li><a href="${link}">${entry.name}${slash}</a>`;
        if (!entry.isDirectory && entry.name.endsWith('.c4')) {
          list += ` [<a href="${link}?edit">edit</a>]`;
          list += ` [<a href="${link.slice(0,-3)}.svg">svg</a>]`;
          list += ` [<a href="${link.slice(0,-3)}.png">png</a>]`;
        }
        list += `</li>`;
      }
      list += '</ul>';
      return new Response(
        `<html><body><h1>Index of ${pathname}</h1>${list}</body></html>`,
        { headers: { 'content-type': 'text/html; charset=utf-8' } }
      );
    }
    const ext = pathname.slice(pathname.lastIndexOf('.'));

    // 3) File GET
    if (method === 'GET') {
      if(url.searchParams.has('edit')) {
        const body = await Deno.readFile(editorFiles[ext]);
        return new Response(body, { headers: { 'content-type': 'text/html' } });
      }      
      if (editorFiles[ext]) {
        if(fileExists) {
          return await serveFile(req, fsPath);
        }else{
          const editorPath = new URL(editorFiles[ext]).pathname.substring(1);
          console.log(`File ${pathname} not found, serving ${editorPath}`)
          // 2) PNG not found → serve the HTML that will render & save it
          return await serveFile(req, editorPath);
        }
      }
      return await serveFile(req, fsPath);
    }
  } catch (err) {
    if (!(err instanceof Deno.errors.NotFound)) {
      return new Response('Server Error', { status: 500 });
    }
  }


  if (method === 'POST') {
    if (!checkAuth(headers)) {
      return new Response('Unauthorized', {
        status: 401,
        headers: { 'WWW-Authenticate': `Basic realm="${REALM}"` },
      });
    }
    const file = await Deno.open(fsPath, { write: true, create: true, truncate: true });
    const ws = new WritableStream({
      write: async (chunk) => {
        await file.write(chunk);
      },
      close: async () => {
        await file.close();
      }
    });
    await req.body!.pipeTo(ws);
    return new Response('Saved');
  }
  return new Response('Not Found', { status: 404 });
});
