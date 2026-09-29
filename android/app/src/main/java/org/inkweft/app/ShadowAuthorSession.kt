// SPDX-License-Identifier: AGPL-3.0-or-later
package org.inkweft.app

import android.content.Context
import org.inkweft.data.*

/** Explicit lab author context. No method resolves an application-owned repository. */
internal class ShadowAuthorSession(context:Context,val replica:ShadowReplica){
    val pages=NotebookPages(replica.db)
    val notes=NoteRepository(replica.db)
    val ink=InkRepository(replica.db)
    val objects=PageObjectRepository(replica.db)
    val study=StudyRepository(replica.db)
    val knowledge=KnowledgeRepository(replica.db)
    val maps=MapGraphAccess(replica.db)
    val rendering=DocumentRendering(context,DocumentRepository(replica.db),java.io.File(replica.directory,"render"))
}
