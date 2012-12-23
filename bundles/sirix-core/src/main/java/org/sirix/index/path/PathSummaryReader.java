package org.sirix.index.path;

import static com.google.common.base.Preconditions.checkNotNull;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

import org.brackit.xquery.atomic.QNm;
import org.brackit.xquery.util.path.Path;
import org.sirix.access.Move;
import org.sirix.access.Moved;
import org.sirix.api.ItemList;
import org.sirix.api.NodeReadTrx;
import org.sirix.api.PageReadTrx;
import org.sirix.api.Session;
import org.sirix.api.visitor.VisitResultType;
import org.sirix.api.visitor.Visitor;
import org.sirix.axis.DescendantAxis;
import org.sirix.axis.IncludeSelf;
import org.sirix.exception.SirixException;
import org.sirix.exception.SirixIOException;
import org.sirix.node.DocumentRootNode;
import org.sirix.node.Kind;
import org.sirix.node.NullNode;
import org.sirix.node.SirixDeweyID;
import org.sirix.node.immutable.ImmutableDocument;
import org.sirix.node.interfaces.NameNode;
import org.sirix.node.interfaces.Record;
import org.sirix.node.interfaces.StructNode;
import org.sirix.node.interfaces.immutable.ImmutableNode;
import org.sirix.page.PageKind;
import org.sirix.service.xml.xpath.AtomicValue;
import org.sirix.settings.Constants;
import org.sirix.settings.Fixed;
import org.sirix.utils.LogWrapper;
import org.sirix.utils.NamePageHash;
import org.slf4j.LoggerFactory;

import com.google.common.base.Objects;
import com.google.common.base.Objects.ToStringHelper;
import com.google.common.base.Optional;

/**
 * Path summary reader organizing the path classes of a resource.
 * 
 * @author Johannes Lichtenberger, University of Konstanz
 * 
 */
public final class PathSummaryReader implements NodeReadTrx {

	/** Logger. */
	private final LogWrapper LOGWRAPPER = new LogWrapper(
			LoggerFactory.getLogger(PathSummaryReader.class));

	/** Strong reference to currently selected node. */
	private StructNode mCurrentNode;

	/** Page reader. */
	private final PageReadTrx mPageReadTrx;

	/** {@link Session} reference. */
	private final Session mSession;

	/** Determines if path summary is closed or not. */
	private boolean mClosed;

	/** Mapping of a path node key to the path node/document root node. */
	private final Map<Long, StructNode> mMapping;

	/**
	 * Private constructor.
	 * 
	 * @param pageReadTrx
	 *          page reader
	 * @param session
	 *          {@link Session} reference
	 */
	private PathSummaryReader(final @Nonnull PageReadTrx pageReadTrx,
			final @Nonnull Session session) {
		mPageReadTrx = pageReadTrx;
		mClosed = false;
		mSession = session;
		try {
			final Optional<? extends Record> node = mPageReadTrx.getRecord(
					Fixed.DOCUMENT_NODE_KEY.getStandardProperty(),
					PageKind.PATHSUMMARYPAGE);
			if (node.isPresent()) {
				mCurrentNode = (StructNode) node.get();
			} else {
				throw new IllegalStateException(
						"Node couldn't be fetched from persistent storage!");
			}
		} catch (final SirixIOException e) {
			LOGWRAPPER.error(e.getMessage(), e.getCause());
		}

		mMapping = new HashMap<>();
		for (final long nodeKey : new DescendantAxis(this, IncludeSelf.YES)) {
			mMapping.put(nodeKey, this.getStructuralNode());
		}
	}

	/**
	 * Get a new path summary reader instance.
	 * 
	 * @param pageReadTrx
	 *          Sirix {@link PageReaderTrx}
	 * @param session
	 *          Sirix {@link Session}
	 * @return new path summary reader instance
	 */
	public static final PathSummaryReader getInstance(
			final @Nonnull PageReadTrx pageReadTrx, final @Nonnull Session session) {
		return new PathSummaryReader(checkNotNull(pageReadTrx),
				checkNotNull(session));
	}

	// package private, only used in writer to keep the mapping always up-to-date
	void putMapping(final @Nonnegative long pathNodeKey,
			final @Nonnull StructNode node) {
		mMapping.put(pathNodeKey, node);
	}

	// package private, only used in writer to keep the mapping always up-to-date
	StructNode removeMapping(final @Nonnegative long pathNodeKey) {
		return mMapping.remove(pathNodeKey);
	}

	@Override
	public boolean isValueNode() {
		return false;
	}

	/**
	 * Get the path node corresponding to the key.
	 * 
	 * @param pathNodeKey
	 *          path node key
	 * @return path node corresponding to the provided key
	 */
	public StructNode getPathNodeForPathNodeKey(
			final @Nonnegative long pathNodeKey) {
		return mMapping.get(pathNodeKey);
	}

	@Override
	public ImmutableNode getNode() {
		assertNotClosed();
		if (mCurrentNode instanceof DocumentRootNode) {
			return ImmutableDocument.of((DocumentRootNode) mCurrentNode);
		}
		return ImmutablePathNode.of((PathNode) mCurrentNode);
	}

	/**
	 * Get path class records for the specified path
	 * 
	 * @param path
	 *          the path for which to get a set of PCRs
	 * @return set of PCRs belonging to the specified path
	 */
	public Set<Long> getPCRsForPath(final @Nonnull Path<QNm> path) {
		return null;
	}

	@Override
	public boolean hasAttributes() {
		return getStructuralNode().hasFirstChild();
	}

	@Override
	public boolean hasChildren() {
		return getStructuralNode().getChildCount() > 0;
	}

	/**
	 * Get a path node.
	 * 
	 * @return {@link PathNode} reference or null for the document root.
	 */
	public PathNode getPathNode() {
		assertNotClosed();
		if (mCurrentNode instanceof PathNode) {
			return (PathNode) mCurrentNode;
		} else {
			return null;
		}
	}

	@Override
	public Move<? extends PathSummaryReader> moveTo(final long nodeKey) {
		assertNotClosed();

		// Remember old node and fetch new one.
		final StructNode oldNode = mCurrentNode;
		Optional<? extends StructNode> newNode;
		try {
			// Immediately return node from item list if node key negative.
			@SuppressWarnings("unchecked")
			final Optional<? extends StructNode> node = (Optional<? extends StructNode>) mPageReadTrx
					.getRecord(nodeKey, PageKind.PATHSUMMARYPAGE);
			newNode = node;
		} catch (final SirixIOException e) {
			newNode = Optional.absent();
		}

		if (newNode.isPresent()) {
			mCurrentNode = newNode.get();
			return Move.moved(this);
		} else {
			mCurrentNode = oldNode;
			return Moved.notMoved();
		}
	}

	@Override
	public Move<? extends PathSummaryReader> moveToParent() {
		assertNotClosed();
		return moveTo(getStructuralNode().getParentKey());
	}

	@Override
	public Move<? extends PathSummaryReader> moveToFirstChild() {
		assertNotClosed();
		if (!getStructuralNode().hasFirstChild()) {
			return Move.notMoved();
		}
		return moveTo(getStructuralNode().getFirstChildKey());
	}

	@Override
	public Move<? extends PathSummaryReader> moveToLeftSibling() {
		assertNotClosed();
		if (!getStructuralNode().hasLeftSibling()) {
			return Move.notMoved();
		}
		return moveTo(getStructuralNode().getLeftSiblingKey());
	}

	@Override
	public Move<? extends PathSummaryReader> moveToRightSibling() {
		assertNotClosed();
		if (!getStructuralNode().hasRightSibling()) {
			return Move.notMoved();
		}
		return moveTo(getStructuralNode().getRightSiblingKey());
	}

	@Override
	public void close() throws SirixException {
		if (!mClosed) {
			// Immediately release all references.
			mCurrentNode = null;
			mClosed = true;

			if (mPageReadTrx != null && !mPageReadTrx.isClosed()) {
				mPageReadTrx.close();
			}
		}
	}

	/**
	 * Make sure that the path summary is not yet closed when calling this method.
	 */
	final void assertNotClosed() {
		if (mClosed) {
			throw new IllegalStateException("Path summary is already closed.");
		}
	}

	@Override
	public Move<? extends PathSummaryReader> moveToDocumentRoot() {
		return moveTo(Fixed.DOCUMENT_NODE_KEY.getStandardProperty());
	}

	/**
	 * Get the current node as a structural node.
	 * 
	 * @return structural node
	 */
	private StructNode getStructuralNode() {
		if (mCurrentNode instanceof StructNode) {
			return (StructNode) mCurrentNode;
		} else {
			return new NullNode(mCurrentNode);
		}
	}

	@Override
	public long getTransactionID() {
		throw new UnsupportedOperationException();
	}

	@Override
	public int getRevisionNumber() throws SirixIOException {
		return mPageReadTrx.getRevisionNumber();
	}

	@Override
	public long getRevisionTimestamp() throws SirixIOException {
		return mPageReadTrx.getActualRevisionRootPage().getRevisionTimestamp();
	}

	@Override
	public long getMaxNodeKey() throws SirixIOException {
		return mPageReadTrx.getActualRevisionRootPage().getMaxPathNodeKey();
	}

	@Override
	public Move<? extends PathSummaryReader> moveToAttribute(
			@Nonnegative int index) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Move<? extends PathSummaryReader> moveToAttributeByName(
			@Nonnull QNm name) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Move<? extends PathSummaryReader> moveToNamespace(
			@Nonnegative int index) {
		throw new UnsupportedOperationException();
	}

	@Override
	public Move<? extends NodeReadTrx> moveToNextFollowing() {
		assertNotClosed();
		while (!getStructuralNode().hasRightSibling() && mCurrentNode.hasParent()) {
			moveToParent();
		}
		return moveToRightSibling();
	}

	@Override
	public String getValue() {
		throw new UnsupportedOperationException();
	}

	@Override
	public QNm getName() {
		assertNotClosed();
		if (mCurrentNode instanceof NameNode) {
			final String uri = mPageReadTrx.getName(
					((NameNode) mCurrentNode).getURIKey(), Kind.NAMESPACE);
			final int prefixKey = ((NameNode) mCurrentNode).getPrefixKey();
			final String prefix = prefixKey == -1 ? "" : mPageReadTrx.getName(
					prefixKey, ((PathNode) mCurrentNode).getPathKind());
			final int localNameKey = ((NameNode) mCurrentNode).getLocalNameKey();
			final String localName = localNameKey == -1 ? "" : mPageReadTrx.getName(
					localNameKey, ((PathNode) mCurrentNode).getPathKind());
			return new QNm(uri, prefix, localName);
		} else {
			return null;
		}
	}

	@Override
	public String getType() {
		assertNotClosed();
		return mPageReadTrx.getName(mCurrentNode.getTypeKey(), getNode().getKind());
	}

	@Override
	public int keyForName(@Nonnull String pName) {
		assertNotClosed();
		return NamePageHash.generateHashForString(pName);
	}

	@Override
	public String nameForKey(int key) {
		assertNotClosed();
		if (mCurrentNode instanceof PathNode) {
			final PathNode node = (PathNode) mCurrentNode;
			return mPageReadTrx.getName(key, node.getPathKind());
		} else {
			return "";
		}
	}

	@Override
	public byte[] rawNameForKey(int key) {
		assertNotClosed();
		if (mCurrentNode instanceof PathNode) {
			final PathNode node = (PathNode) mCurrentNode;
			return mPageReadTrx.getName(key, node.getPathKind()).getBytes(
					Constants.DEFAULT_ENCODING);
		} else {
			return "".getBytes(Constants.DEFAULT_ENCODING);
		}
	}

	@Override
	public ItemList<AtomicValue> getItemList() {
		throw new UnsupportedOperationException();
	}

	@Override
	public boolean isClosed() {
		return mClosed;
	}

	@Override
	public Session getSession() {
		assertNotClosed();
		return mSession;
	}

	@Override
	public synchronized NodeReadTrx cloneInstance() throws SirixException {
		assertNotClosed();
		final NodeReadTrx rtx = getInstance(
				mSession.beginPageReadTrx(mPageReadTrx.getRevisionNumber()), mSession);
		rtx.moveTo(mCurrentNode.getNodeKey());
		return rtx;
	}

	@Override
	public Move<? extends PathSummaryReader> moveToLastChild() {
		assertNotClosed();
		if (getStructuralNode().hasFirstChild()) {
			moveToFirstChild();

			while (getStructuralNode().hasRightSibling()) {
				moveToRightSibling();
			}

			return Moved.moved(this);
		}
		return Moved.notMoved();
	}

	@Override
	public int getNameCount(@Nonnull String name, @Nonnull Kind kind) {
		assertNotClosed();
		return mPageReadTrx.getNameCount(NamePageHash.generateHashForString(name),
				kind);
	}

	@Override
	public String toString() {
		final ToStringHelper helper = Objects.toStringHelper(this);

		if (mCurrentNode instanceof PathNode) {
			final PathNode node = (PathNode) mCurrentNode;
			helper.add("uri",
					mPageReadTrx.getName(node.getURIKey(), node.getPathKind()));
			helper.add("prefix",
					mPageReadTrx.getName(node.getPrefixKey(), node.getPathKind()));
			helper.add("localName",
					mPageReadTrx.getName(node.getLocalNameKey(), node.getPathKind()));
		}

		helper.add("node", mCurrentNode);
		return helper.toString();
	}

	/**
	 * Get level of currently selected path node.
	 * 
	 * @return level of currently selected path node
	 */
	public int getLevel() {
		assertNotClosed();
		if (mCurrentNode instanceof PathNode) {
			return getPathNode().getLevel();
		}
		return 0;
	}

	@Override
	public boolean hasNode(final @Nonnegative long key) {
		assertNotClosed();
		final long currNodeKey = mCurrentNode.getNodeKey();
		final boolean retVal = moveTo(key).hasMoved();
		final boolean movedBack = moveTo(currNodeKey).hasMoved();
		assert movedBack : "moveTo(currNodeKey) must succeed!";
		return retVal;
	}

	@Override
	public boolean hasParent() {
		assertNotClosed();
		return mCurrentNode.hasParent();
	}

	@Override
	public boolean hasFirstChild() {
		assertNotClosed();
		return getStructuralNode().hasFirstChild();
	}

	@Override
	public boolean hasLastChild() {
		assertNotClosed();
		final long nodeKey = mCurrentNode.getNodeKey();
		final boolean retVal = moveToLastChild() == null ? false : true;
		moveTo(nodeKey);
		return retVal;
	}

	@Override
	public boolean hasLeftSibling() {
		assertNotClosed();
		return getStructuralNode().hasLeftSibling();
	}

	@Override
	public boolean hasRightSibling() {
		assertNotClosed();
		return getStructuralNode().hasRightSibling();
	}

	@Override
	public VisitResultType acceptVisitor(final @Nonnull Visitor visitor) {
		throw new UnsupportedOperationException();
	}

	@Override
	public long getNodeKey() {
		assertNotClosed();
		return mCurrentNode.getNodeKey();
	}

	@Override
	public long getLeftSiblingKey() {
		assertNotClosed();
		return getStructuralNode().getLeftSiblingKey();
	}

	@Override
	public long getRightSiblingKey() {
		assertNotClosed();
		return getStructuralNode().getRightSiblingKey();
	}

	@Override
	public long getFirstChildKey() {
		assertNotClosed();
		return getStructuralNode().getFirstChildKey();
	}

	@Override
	public long getLastChildKey() {
		assertNotClosed();
		if (getStructuralNode().hasFirstChild()) {
			moveToFirstChild();
			while (getStructuralNode().hasRightSibling()) {
				moveToRightSibling();
			}
			return mCurrentNode.getNodeKey();
		}
		return Fixed.NULL_NODE_KEY.getStandardProperty();
	}

	@Override
	public long getParentKey() {
		assertNotClosed();
		return mCurrentNode.getParentKey();
	}

	@Override
	public int getAttributeCount() {
		assertNotClosed();
		return 0;
	}

	@Override
	public int getNamespaceCount() {
		assertNotClosed();
		return 0;
	}

	@Override
	public Kind getKind() {
		assertNotClosed();
		return Kind.PATH;
	}

	@Override
	public boolean isNameNode() {
		assertNotClosed();
		if (mCurrentNode instanceof NameNode) {
			return true;
		}
		return false;
	}

	@Override
	public int getTypeKey() {
		assertNotClosed();
		return mCurrentNode.getTypeKey();
	}

	@Override
	public long getAttributeKey(final @Nonnegative int index) {
		assertNotClosed();
		return -1;
	}

	@Override
	public long getPathNodeKey() {
		assertNotClosed();
		return -1;
	}

	@Override
	public Kind getPathKind() {
		assertNotClosed();
		if (mCurrentNode instanceof PathNode) {
			return ((PathNode) mCurrentNode).getPathKind();
		}
		return Kind.NULL;
	}

	@Override
	public boolean isStructuralNode() {
		assertNotClosed();
		return true;
	}

	@Override
	public int getURIKey() {
		assertNotClosed();
		if (mCurrentNode instanceof NameNode) {
			return ((NameNode) mCurrentNode).getURIKey();
		}
		return -1;
	}

	@Override
	public int getPrefixKey() {
		assertNotClosed();
		if (mCurrentNode instanceof NameNode) {
			return ((NameNode) mCurrentNode).getPrefixKey();
		}
		return -1;
	}

	@Override
	public int getLocalNameKey() {
		assertNotClosed();
		if (mCurrentNode instanceof NameNode) {
			return ((NameNode) mCurrentNode).getLocalNameKey();
		}
		return -1;
	}

	@Override
	public long getHash() {
		assertNotClosed();
		return mCurrentNode.getHash();
	}

	@Override
	public List<Long> getAttributeKeys() {
		assertNotClosed();
		return Collections.emptyList();
	}

	@Override
	public List<Long> getNamespaceKeys() {
		assertNotClosed();
		return Collections.emptyList();
	}

	@Override
	public byte[] getRawValue() {
		assertNotClosed();
		return null;
	}

	@Override
	public long getChildCount() {
		assertNotClosed();
		return getStructuralNode().getChildCount();
	}

	@Override
	public long getDescendantCount() {
		assertNotClosed();
		return getStructuralNode().getDescendantCount();
	}

	@Override
	public String getNamespaceURI() {
		assertNotClosed();
		return null;
	}

	@Override
	public Kind getFirstChildKind() {
		assertNotClosed();
		return Kind.PATH;
	}

	@Override
	public Kind getLastChildKind() {
		assertNotClosed();
		return Kind.PATH;
	}

	@Override
	public Kind getLeftSiblingKind() {
		assertNotClosed();
		return Kind.PATH;
	}

	@Override
	public Kind getParentKind() {
		assertNotClosed();
		if (mCurrentNode.getParentKey() == Fixed.DOCUMENT_NODE_KEY
				.getStandardProperty()) {
			return Kind.DOCUMENT;
		}
		if (mCurrentNode.getParentKey() == Fixed.NULL_NODE_KEY
				.getStandardProperty()) {
			return Kind.UNKNOWN;
		}
		return Kind.PATH;
	}

	@Override
	public Kind getRightSiblingKind() {
		assertNotClosed();
		return Kind.PATH;
	}

	/**
	 * Get references.
	 * 
	 * @return number of references of a node
	 */
	public int getReferences() {
		assertNotClosed();
		if (mCurrentNode.getKind() == Kind.DOCUMENT) {
			return 1;
		} else {
			return getPathNode().getReferences();
		}
	}

	@Override
	public boolean isElement() {
		assertNotClosed();
		return false;
	}

	@Override
	public boolean isText() {
		assertNotClosed();
		return false;
	}

	@Override
	public boolean isDocumentRoot() {
		assertNotClosed();
		if (mCurrentNode.getKind() == Kind.DOCUMENT) {
			return true;
		}
		return false;
	}

	@Override
	public boolean isComment() {
		assertNotClosed();
		return false;
	}

	@Override
	public boolean isAttribute() {
		assertNotClosed();
		// Not sure about this, actually no PathNode is an attribute, but it might
		// represent an attribute.
		return false;
	}

	@Override
	public boolean isNamespace() {
		assertNotClosed();
		// Not sure about this, actually no PathNode is an attribute, but it might
		// represent a namespace.
		return false;
	}

	@Override
	public boolean isPI() {
		assertNotClosed();
		return false;
	}

	@Override
	public boolean hasNamespaces() {
		assertNotClosed();
		return false;
	}

	@Override
	public Move<? extends NodeReadTrx> moveToPrevious() {
		assertNotClosed();
		final StructNode node = getStructuralNode();
		if (node.hasLeftSibling()) {
			// Left sibling node.
			Move<? extends NodeReadTrx> leftSiblMove = moveTo(node
					.getLeftSiblingKey());
			// Now move down to rightmost descendant node if it has one.
			while (leftSiblMove.get().hasFirstChild()) {
				leftSiblMove = leftSiblMove.get().moveToLastChild();
			}
			return leftSiblMove;
		}
		// Parent node.
		return moveTo(node.getParentKey());
	}

	@Override
	public Move<? extends NodeReadTrx> moveToNext() {
		assertNotClosed();
		final StructNode node = getStructuralNode();
		if (node.hasRightSibling()) {
			// Right sibling node.
			return moveTo(node.getRightSiblingKey());
		}
		// Next following node.
		return moveToNextFollowing();
	}

	@Override
	public Optional<SirixDeweyID> getDeweyID() {
		assertNotClosed();
		return mCurrentNode.getDeweyID();
	}

	@Override
	public Optional<SirixDeweyID> getLeftSiblingDeweyID() {
		assertNotClosed();
		return Optional.<SirixDeweyID> absent();
	}

	@Override
	public Optional<SirixDeweyID> getRightSiblingDeweyID() {
		assertNotClosed();
		return Optional.<SirixDeweyID> absent();
	}

	@Override
	public Optional<SirixDeweyID> getParentDeweyID() {
		assertNotClosed();
		return Optional.<SirixDeweyID> absent();
	}

	@Override
	public Optional<SirixDeweyID> getFirstChildDeweyID() {
		assertNotClosed();
		return Optional.<SirixDeweyID> absent();
	}
}